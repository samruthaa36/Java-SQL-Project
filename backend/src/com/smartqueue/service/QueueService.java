package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.service.LiveQueue.ServiceLive;
import com.smartqueue.util.Log;
import com.smartqueue.util.Times;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token life-cycle and counter control.
 *
 *   WAITING -> CALLED -> SERVING -> COMPLETED
 *                 |          |
 *                 |          +--> ON_HOLD --> WAITING
 *                 +--> SKIPPED (no-show) --> WAITING (restored by staff)
 *   WAITING / CALLED / ON_HOLD / SERVING --> CANCELLED
 *
 * Every state change runs inside ONE global lock + ONE database transaction, so two counters can never
 * call the same token and a customer can never get two active tokens.
 */
public final class QueueService {

    public static final ReentrantLock LOCK = new ReentrantLock();
    public static final String ACTIVE_IN = "('WAITING','CALLED','SERVING','ON_HOLD')";

    private static final String TOKEN_SELECT =
            "SELECT t.*, s.name AS service_name, s.prefix AS service_prefix, s.color AS service_color, s.icon AS service_icon, "
                    + "c.name AS counter_name, st.full_name AS served_by_name "
                    + "FROM tokens t JOIN services s ON s.id = t.service_id "
                    + "LEFT JOIN counters c ON c.id = t.counter_id "
                    + "LEFT JOIN users st ON st.id = t.served_by ";

    private QueueService() {
    }

    static <T> T locked(Callable<T> work) {
        LOCK.lock();
        try {
            return Database.tx(work);
        } finally {
            LOCK.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Reading tokens
    // ------------------------------------------------------------------
    public static String tokenSelect() {
        return TOKEN_SELECT;
    }

    public static Row findToken(long id) {
        return Database.queryOne(TOKEN_SELECT + "WHERE t.id = ?", id);
    }

    public static Row requireToken(long id) {
        Row t = findToken(id);
        if (t == null) {
            throw ApiException.notFound("Token not found.");
        }
        return t;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Adds waited / service durations (in minutes) to a token row. */
    public static Row enrich(Row t) {
        Row r = t.copy();
        LocalDateTime now = Times.now();
        LocalDateTime created = t.time("createdAt");
        LocalDateTime called = t.time("calledAt");
        LocalDateTime ended = t.time("endedAt");
        LocalDateTime serving = t.time("servingAt");
        String st = t.str("status");
        Double waited = null;
        if (created != null) {
            if (called != null) {
                waited = Times.minutes(created, called);
            } else if ("WAITING".equals(st) || "ON_HOLD".equals(st)) {
                waited = Times.minutes(created, now);
            } else if (ended != null) {
                waited = Times.minutes(created, ended);
            }
        }
        r.put("waitedMinutes", waited == null ? null : round1(Math.max(0, waited)));
        double svc = t.integer("serviceSeconds") / 60.0;
        if ("SERVING".equals(st) && serving != null) {
            svc += Math.max(0, Times.minutes(serving, now));
        }
        r.put("serviceMinutes", round1(svc));
        return r;
    }

    /** Token as sent to the browser: live position / ETA while waiting, plus durations. */
    public static Row view(Row t) {
        ServiceLive live = "WAITING".equals(t.str("status")) ? LiveQueue.of(t.lng("serviceId")) : null;
        return enrich(LiveQueue.decorate(t, live));
    }

    // ------------------------------------------------------------------
    // Issuing tokens
    // ------------------------------------------------------------------
    public static Row issueOnline(Row user, long serviceId, String notes) {
        int priority = user.bool("isPriority") ? 1 : 0;
        String reason = user.str("priorityReason");
        return issue(null, user, serviceId, user.str("fullName"), user.str("phone"), priority, reason, notes, "ONLINE");
    }

    public static Row issueWalkIn(Row staff, long serviceId, String customerName, String phone, int priority,
                                  String reason, String notes) {
        String name = customerName == null || customerName.trim().isEmpty()
                ? "Walk-in guest" : Validate.text(customerName, "Customer name", 2, 100);
        String ph = Validate.phone(phone);
        Validate.rangeInt(priority, "Priority", 0, 2);
        String why = Validate.optional(reason, "Priority reason", 100);
        if (priority == 2 && why == null) {
            why = "Emergency";
        }
        if (priority == 1 && why == null) {
            why = "Priority customer";
        }
        return issue(staff, null, serviceId, name, ph, priority, why, Validate.optional(notes, "Notes", 255), "WALK_IN");
    }

    private static Row issue(Row issuer, Row customer, long serviceId, String name, String phone, int priority,
                             String reason, String notes, String source) {
        String cleanNotes = Validate.optional(notes, "Notes", 255);
        return locked(() -> {
            Row svc = Database.queryOne("SELECT * FROM services WHERE id = ?", serviceId);
            if (svc == null) {
                throw ApiException.notFound("Service not found.");
            }
            if (!svc.bool("isActive")) {
                throw ApiException.conflict(svc.str("name") + " is not accepting new tokens right now.");
            }
            Long customerId = customer == null ? null : customer.lng("id");
            if (customerId != null) {
                Row dup = Database.queryFirst("SELECT token_code FROM tokens WHERE user_id = ? AND status IN " + ACTIVE_IN
                        + " ORDER BY id", customerId);
                if (dup != null) {
                    throw ApiException.conflict("You already have an active token (" + dup.str("tokenCode")
                            + "). Please finish or cancel it before taking a new one.");
                }
            }
            long inQueue = Database.scalarLong("SELECT COUNT(*) FROM tokens WHERE service_id = ? "
                    + "AND status IN ('WAITING','CALLED','ON_HOLD')", serviceId);
            if (inQueue >= Settings.getInt("max_queue_size")) {
                throw ApiException.conflict("The " + svc.str("name") + " queue is full right now. Please try again shortly.");
            }
            LocalDateTime now = Times.now();
            LocalDate today = Times.today();
            long seq = Database.scalarLong("SELECT COALESCE(MAX(token_seq), 0) + 1 FROM tokens WHERE service_id = ? AND token_date = ?",
                    serviceId, today);
            String code = svc.str("prefix") + String.format("%03d", seq);
            Long issuedBy = issuer == null ? null : Long.valueOf(issuer.lng("id"));
            long id = Database.insert("INSERT INTO tokens (id, service_id, token_seq, token_code, token_date, user_id, customer_name, "
                            + "customer_phone, priority, priority_reason, status, source, issued_by, notes, created_at, queue_time) "
                            + "VALUES (tokens_seq.NEXTVAL, ?,?,?,?,?,?,?,?,?,'WAITING',?,?,?,?,?)",
                    serviceId, seq, code, today, customerId, name, phone, priority, priority > 0 ? reason : null,
                    source, issuedBy, cleanNotes, now, now);

            // estimate + notification BEFORE any auto-assignment moves the token
            Row created = findToken(id);
            Row dec = LiveQueue.decorate(created, LiveQueue.of(serviceId));
            int eta = dec.get("position") == null ? 0 : dec.integer("etaMinutes");
            Database.update("UPDATE tokens SET estimated_wait_min = ? WHERE id = ?", eta, id);
            if (customerId != null) {
                String where = dec.get("position") == null ? "" : " You are #" + dec.integer("position") + " in line"
                        + (eta > 0 ? " - estimated wait about " + eta + " min." : " - you are up next.");
                NotificationService.notify(customerId, id, "TOKEN_CREATED", "Token " + code + " generated",
                        "Your " + svc.str("name") + " token is ready." + where);
            }
            if (issuer != null) {
                ActivityLog.log(issuer, "WALK_IN_TOKEN", "token", id, "Issued " + code + " to " + name
                        + (priority > 0 ? " (priority " + priority + ")" : ""));
            }
            afterQueueChange(serviceId);
            return view(findToken(id));
        });
    }

    // ------------------------------------------------------------------
    // Counters of a staff member
    // ------------------------------------------------------------------
    public static Row counterOfStaff(long staffId) {
        return Database.queryOne("SELECT c.*, s.name AS service_name FROM counters c JOIN services s ON s.id = c.service_id "
                + "WHERE c.staff_id = ? AND c.is_deleted = 0 AND c.status IN ('OPEN','BREAK')", staffId);
    }

    private static Row requireOpenCounter(Row staff) {
        Row c = counterOfStaff(staff.lng("id"));
        if (c == null) {
            throw ApiException.conflict("Open a counter first.");
        }
        if (!"OPEN".equals(c.str("status"))) {
            throw ApiException.conflict("Your counter is on a break. Resume it first.");
        }
        return c;
    }

    private static Row currentTokenOf(long counterId) {
        return Database.queryFirst("SELECT id, status, token_code FROM tokens WHERE counter_id = ? AND status IN ('CALLED','SERVING') "
                + "ORDER BY id", counterId);
    }

    public static Row openCounter(Row staff, long counterId) {
        return locked(() -> {
            long staffId = staff.lng("id");
            Row mine = counterOfStaff(staffId);
            if (mine != null) {
                throw ApiException.conflict("You already have " + mine.str("name") + " open. Close it before opening another one.");
            }
            Row c = Database.queryOne("SELECT * FROM counters WHERE id = ? AND is_deleted = 0", counterId);
            if (c == null) {
                throw ApiException.notFound("Counter not found.");
            }
            int n = Database.update("UPDATE counters SET status = 'OPEN', staff_id = ?, opened_at = ? "
                    + "WHERE id = ? AND status = 'CLOSED'", staffId, Times.now(), counterId);
            if (n == 0) {
                throw ApiException.conflict(c.str("name") + " is already in use by another staff member.");
            }
            ActivityLog.log(staff, "COUNTER_OPENED", "counter", counterId, c.str("name") + " opened");
            afterQueueChange(c.lng("serviceId"));
            return counterOfStaff(staffId);
        });
    }

    public static void closeCounter(Row staff) {
        locked(() -> {
            Row c = counterOfStaff(staff.lng("id"));
            if (c == null) {
                throw ApiException.conflict("You do not have an open counter.");
            }
            if (currentTokenOf(c.lng("id")) != null) {
                throw ApiException.conflict("Finish, hold or skip the current customer before closing the counter.");
            }
            Database.update("UPDATE counters SET status = 'CLOSED', staff_id = NULL, opened_at = NULL WHERE id = ?", c.lng("id"));
            ActivityLog.log(staff, "COUNTER_CLOSED", "counter", c.lng("id"), c.str("name") + " closed");
            return null;
        });
    }

    public static Row setBreak(Row staff, boolean onBreak) {
        return locked(() -> {
            Row c = counterOfStaff(staff.lng("id"));
            if (c == null) {
                throw ApiException.conflict("You do not have an open counter.");
            }
            if (onBreak) {
                if (!"OPEN".equals(c.str("status"))) {
                    return c;
                }
                if (currentTokenOf(c.lng("id")) != null) {
                    throw ApiException.conflict("Finish, hold or skip the current customer before starting a break.");
                }
                Database.update("UPDATE counters SET status = 'BREAK' WHERE id = ?", c.lng("id"));
            } else {
                Database.update("UPDATE counters SET status = 'OPEN' WHERE id = ?", c.lng("id"));
                afterQueueChange(c.lng("serviceId"));
            }
            return counterOfStaff(staff.lng("id"));
        });
    }

    /** Administrator opens a closed counter on behalf of a staff member. */
    public static void adminOpenCounter(Row admin, long counterId, long staffId) {
        locked(() -> {
            Row staff = Database.queryOne("SELECT * FROM users WHERE id = ? AND role = 'STAFF' AND is_active = 1", staffId);
            if (staff == null) {
                throw ApiException.notFound("Staff member not found or inactive.");
            }
            if (counterOfStaff(staffId) != null) {
                throw ApiException.conflict(staff.str("fullName") + " is already working at a counter.");
            }
            Row c = Database.queryOne("SELECT * FROM counters WHERE id = ? AND is_deleted = 0", counterId);
            if (c == null) {
                throw ApiException.notFound("Counter not found.");
            }
            int n = Database.update("UPDATE counters SET status = 'OPEN', staff_id = ?, opened_at = ? WHERE id = ? AND status = 'CLOSED'",
                    staffId, Times.now(), counterId);
            if (n == 0) {
                throw ApiException.conflict(c.str("name") + " is not closed.");
            }
            NotificationService.notify(staffId, null, "COUNTER", "Counter opened for you",
                    admin.str("fullName") + " opened " + c.str("name") + " and assigned it to you.");
            ActivityLog.log(admin, "COUNTER_OPENED", "counter", counterId, c.str("name") + " opened for " + staff.str("fullName"));
            afterQueueChange(c.lng("serviceId"));
            return null;
        });
    }

    /** Administrator closes a counter. A called customer goes back to the queue; a served one is put on hold. */
    public static void adminCloseCounter(Row admin, long counterId) {
        locked(() -> {
            Row c = Database.queryOne("SELECT * FROM counters WHERE id = ? AND is_deleted = 0", counterId);
            if (c == null) {
                throw ApiException.notFound("Counter not found.");
            }
            if ("CLOSED".equals(c.str("status"))) {
                return null;
            }
            Row cur = Database.queryFirst("SELECT * FROM tokens WHERE counter_id = ? AND status IN ('CALLED','SERVING') ORDER BY id", counterId);
            LocalDateTime now = Times.now();
            if (cur != null) {
                if ("CALLED".equals(cur.str("status"))) {
                    Database.update("UPDATE tokens SET status = 'WAITING', counter_id = NULL, approach_notified = 0, next_notified = 0 WHERE id = ?",
                            cur.lng("id"));
                    NotificationService.notify(cur.lngOrNull("userId"), cur.lng("id"), "QUEUE", "Counter changed",
                            "Token " + cur.str("tokenCode") + " is back in the queue because the counter was closed.");
                } else {
                    Database.update("UPDATE tokens SET status = 'ON_HOLD', service_seconds = service_seconds + ?, serving_at = NULL WHERE id = ?",
                            accrued(cur, now), cur.lng("id"));
                    NotificationService.notify(cur.lngOrNull("userId"), cur.lng("id"), "HOLD", "Token on hold",
                            "Token " + cur.str("tokenCode") + " is on hold. Staff will call you again shortly.");
                }
            }
            Long staffId = c.lngOrNull("staffId");
            Database.update("UPDATE counters SET status = 'CLOSED', staff_id = NULL, opened_at = NULL WHERE id = ?", counterId);
            if (staffId != null) {
                NotificationService.notify(staffId, null, "COUNTER", "Counter closed",
                        c.str("name") + " was closed by an administrator.");
            }
            ActivityLog.log(admin, "COUNTER_CLOSED", "counter", counterId, c.str("name") + " closed by admin");
            afterQueueChange(c.lng("serviceId"));
            return null;
        });
    }

    // ------------------------------------------------------------------
    // Calling and serving
    // ------------------------------------------------------------------
    public static Row callNext(Row staff) {
        return locked(() -> {
            Row counter = requireOpenCounter(staff);
            if (currentTokenOf(counter.lng("id")) != null) {
                throw ApiException.conflict("Finish, hold or skip the current customer before calling the next one.");
            }
            ServiceLive live = LiveQueue.of(counter.lng("serviceId"));
            if (live == null || live.waiting.isEmpty()) {
                throw ApiException.conflict("No customers are waiting in the " + counter.str("serviceName") + " queue.");
            }
            Row t = live.waiting.get(0);
            doCall(counter, t);
            afterQueueChange(live.id);
            return view(findToken(t.lng("id")));
        });
    }

    /** Staff picks a specific waiting (or on-hold) token instead of the automatic next one. */
    public static Row callToken(Row staff, long tokenId) {
        return locked(() -> {
            Row counter = requireOpenCounter(staff);
            if (currentTokenOf(counter.lng("id")) != null) {
                throw ApiException.conflict("Finish, hold or skip the current customer before calling another one.");
            }
            Row t = requireToken(tokenId);
            if (t.lng("serviceId") != counter.lng("serviceId")) {
                throw ApiException.conflict("This token belongs to another service queue.");
            }
            if (!"WAITING".equals(t.str("status")) && !"ON_HOLD".equals(t.str("status"))) {
                throw ApiException.conflict("Token " + t.str("tokenCode") + " is " + t.str("status").toLowerCase().replace('_', ' ')
                        + " and cannot be called.");
            }
            doCall(counter, t);
            afterQueueChange(counter.lng("serviceId"));
            return view(findToken(tokenId));
        });
    }

    private static void doCall(Row counter, Row token) {
        LocalDateTime now = Times.now();
        int n = Database.update("UPDATE tokens SET status = 'CALLED', counter_id = ?, served_by = ?, called_at = COALESCE(called_at, ?), "
                        + "last_called_at = ?, call_count = call_count + 1, approach_notified = 1, next_notified = 1 "
                        + "WHERE id = ? AND status IN ('WAITING','ON_HOLD')",
                counter.lng("id"), counter.lngOrNull("staffId"), now, now, token.lng("id"));
        if (n == 0) {
            throw ApiException.conflict("This token is no longer available.");
        }
        int pr = token.integer("priority");
        long serviceId = token.lng("serviceId");
        if (pr == 1) {
            Database.update("UPDATE services SET priority_streak = priority_streak + 1 WHERE id = ?", serviceId);
        } else if (pr == 0) {
            Database.update("UPDATE services SET priority_streak = 0 WHERE id = ?", serviceId);
        }
        NotificationService.notify(token.lngOrNull("userId"), token.lng("id"), "CALLED", "It's your turn!",
                "Token " + token.str("tokenCode") + " is now being served. Please proceed to " + counter.str("name") + ".");
    }

    /** Returns a token that is assigned to the staff member's counter and is in one of the given statuses. */
    private static Row ownToken(Row staff, long tokenId, String... statuses) {
        Row counter = counterOfStaff(staff.lng("id"));
        if (counter == null) {
            throw ApiException.conflict("You do not have an open counter.");
        }
        Row t = requireToken(tokenId);
        if (t.lngOrNull("counterId") == null || t.lng("counterId") != counter.lng("id")) {
            throw ApiException.conflict("This token is not assigned to your counter.");
        }
        for (String s : statuses) {
            if (s.equals(t.str("status"))) {
                return t;
            }
        }
        throw ApiException.conflict("Token " + t.str("tokenCode") + " is " + t.str("status").toLowerCase().replace('_', ' ')
                + ", so this action is not possible.");
    }

    private static long accrued(Row t, LocalDateTime now) {
        LocalDateTime serving = t.time("servingAt");
        if ("SERVING".equals(t.str("status")) && serving != null) {
            return Math.max(0, Times.seconds(serving, now));
        }
        return 0;
    }

    public static Row start(Row staff, long tokenId) {
        return locked(() -> {
            Row t = ownToken(staff, tokenId, "CALLED");
            Database.update("UPDATE tokens SET status = 'SERVING', serving_at = ? WHERE id = ? AND status = 'CALLED'", Times.now(), tokenId);
            return view(findToken(t.lng("id")));
        });
    }

    public static Row complete(Row staff, long tokenId, String notes) {
        String cleanNotes = Validate.optional(notes, "Notes", 255);
        return locked(() -> {
            Row t = ownToken(staff, tokenId, "SERVING");
            LocalDateTime now = Times.now();
            long total = Math.max(1, t.integer("serviceSeconds") + accrued(t, now));
            Database.update("UPDATE tokens SET status = 'COMPLETED', ended_at = ?, service_seconds = ?, notes = COALESCE(?, notes) "
                    + "WHERE id = ? AND status = 'SERVING'", now, total, cleanNotes, tokenId);
            NotificationService.notify(t.lngOrNull("userId"), tokenId, "COMPLETED", "Thank you for visiting",
                    "Token " + t.str("tokenCode") + " is completed. Tell us how it went - rate your visit in History.");
            afterQueueChange(t.lng("serviceId"));
            return view(findToken(tokenId));
        });
    }

    public static Row skip(Row staff, long tokenId) {
        return locked(() -> {
            Row t = ownToken(staff, tokenId, "CALLED");
            markSkipped(t, "Customer did not reach the counter");
            afterQueueChange(t.lng("serviceId"));
            return view(findToken(tokenId));
        });
    }

    private static void markSkipped(Row t, String reason) {
        Database.update("UPDATE tokens SET status = 'SKIPPED', ended_at = ?, close_reason = ? WHERE id = ? AND status = 'CALLED'",
                Times.now(), reason, t.lng("id"));
        NotificationService.notify(t.lngOrNull("userId"), t.lng("id"), "SKIPPED", "You missed your turn",
                "Token " + t.str("tokenCode") + " was skipped because you did not reach the counter. "
                        + "Please ask the staff to restore your token.");
    }

    public static Row recall(Row staff, long tokenId) {
        return locked(() -> {
            Row t = ownToken(staff, tokenId, "CALLED");
            Database.update("UPDATE tokens SET last_called_at = ?, call_count = call_count + 1 WHERE id = ?", Times.now(), tokenId);
            NotificationService.notify(t.lngOrNull("userId"), tokenId, "RECALL", "Reminder: please come to the counter",
                    "Token " + t.str("tokenCode") + " - " + t.str("counterName") + " is waiting for you.");
            return view(findToken(tokenId));
        });
    }

    public static Row hold(Row staff, long tokenId) {
        return locked(() -> {
            Row t = ownToken(staff, tokenId, "CALLED", "SERVING");
            Database.update("UPDATE tokens SET status = 'ON_HOLD', service_seconds = service_seconds + ?, serving_at = NULL "
                    + "WHERE id = ? AND status IN ('CALLED','SERVING')", accrued(t, Times.now()), tokenId);
            NotificationService.notify(t.lngOrNull("userId"), tokenId, "HOLD", "Your token is on hold",
                    "Token " + t.str("tokenCode") + " is on hold. Staff will call you again shortly.");
            afterQueueChange(t.lng("serviceId"));
            return view(findToken(tokenId));
        });
    }

    // ------------------------------------------------------------------
    // Actions that staff AND administrators may perform on any queue token
    // ------------------------------------------------------------------

    /** Staff may only touch tokens of their own counter's service; administrators may touch all. */
    private static void authorize(Row actor, Row token) {
        if ("ADMIN".equals(actor.str("role"))) {
            return;
        }
        Row counter = counterOfStaff(actor.lng("id"));
        if (counter == null) {
            throw ApiException.conflict("Open a counter first.");
        }
        if (counter.lng("serviceId") != token.lng("serviceId")) {
            throw ApiException.forbidden("This token belongs to another service queue.");
        }
    }

    public static Row resume(Row actor, long tokenId) {
        return locked(() -> {
            Row t = requireToken(tokenId);
            authorize(actor, t);
            if (!"ON_HOLD".equals(t.str("status"))) {
                throw ApiException.conflict("Token " + t.str("tokenCode") + " is not on hold.");
            }
            Database.update("UPDATE tokens SET status = 'WAITING', counter_id = NULL, approach_notified = 0, next_notified = 0 WHERE id = ? "
                    + "AND status = 'ON_HOLD'", tokenId);
            NotificationService.notify(t.lngOrNull("userId"), tokenId, "QUEUE", "Back in the queue",
                    "Token " + t.str("tokenCode") + " is back in the queue.");
            afterQueueChange(t.lng("serviceId"));
            return view(findToken(tokenId));
        });
    }

    /** Puts a skipped (no-show) token back at the END of its queue. */
    public static Row requeue(Row actor, long tokenId) {
        return locked(() -> {
            Row t = requireToken(tokenId);
            authorize(actor, t);
            if (!"SKIPPED".equals(t.str("status"))) {
                throw ApiException.conflict("Only skipped tokens can be restored to the queue.");
            }
            if (!Times.today().equals(t.date("tokenDate"))) {
                throw ApiException.conflict("This token is from a previous day and cannot be restored.");
            }
            if (t.lngOrNull("userId") != null && Database.scalarLong("SELECT COUNT(*) FROM tokens WHERE user_id = ? AND status IN "
                    + ACTIVE_IN, t.lng("userId")) > 0) {
                throw ApiException.conflict("This customer already has another active token.");
            }
            Database.update("UPDATE tokens SET status = 'WAITING', counter_id = NULL, queue_time = ?, ended_at = NULL, close_reason = NULL, "
                    + "approach_notified = 0, next_notified = 0 WHERE id = ? AND status = 'SKIPPED'", Times.now(), tokenId);
            NotificationService.notify(t.lngOrNull("userId"), tokenId, "QUEUE", "Token restored",
                    "Token " + t.str("tokenCode") + " was restored to the end of the queue.");
            ActivityLog.log(actor, "TOKEN_RESTORED", "token", tokenId, "Restored skipped token " + t.str("tokenCode"));
            afterQueueChange(t.lng("serviceId"));
            return view(findToken(tokenId));
        });
    }

    public static Row setPriority(Row actor, long tokenId, int priority, String reason) {
        Validate.rangeInt(priority, "Priority", 0, 2);
        String why = Validate.optional(reason, "Reason", 100);
        return locked(() -> {
            Row t = requireToken(tokenId);
            authorize(actor, t);
            if (!"WAITING".equals(t.str("status")) && !"ON_HOLD".equals(t.str("status"))) {
                throw ApiException.conflict("Priority can only be changed while a token is waiting.");
            }
            String r = priority == 0 ? null : (why != null ? why : (priority == 2 ? "Emergency" : "Priority customer"));
            Database.update("UPDATE tokens SET priority = ?, priority_reason = ? WHERE id = ?", priority, r, tokenId);
            String label = priority == 2 ? "EMERGENCY" : (priority == 1 ? "PRIORITY" : "NORMAL");
            NotificationService.notify(t.lngOrNull("userId"), tokenId, "QUEUE", "Queue position updated",
                    "Token " + t.str("tokenCode") + " is now a " + label + " token.");
            ActivityLog.log(actor, "TOKEN_PRIORITY", "token", tokenId, t.str("tokenCode") + " set to " + label
                    + (r != null ? " (" + r + ")" : ""));
            afterQueueChange(t.lng("serviceId"));
            return view(findToken(tokenId));
        });
    }

    /** Moves a waiting token to the front or back of its own priority class. */
    public static Row move(Row actor, long tokenId, String where) {
        if (!"front".equals(where) && !"back".equals(where)) {
            throw ApiException.badRequest("Position must be 'front' or 'back'.");
        }
        return locked(() -> {
            Row t = requireToken(tokenId);
            authorize(actor, t);
            if (!"WAITING".equals(t.str("status"))) {
                throw ApiException.conflict("Only waiting tokens can be reordered.");
            }
            LocalDateTime target;
            if ("front".equals(where)) {
                Row m = Database.queryOne("SELECT MIN(queue_time) AS m FROM tokens WHERE service_id = ? AND status = 'WAITING' AND priority = ?",
                        t.lng("serviceId"), t.integer("priority"));
                LocalDateTime min = m == null ? null : m.time("m");
                target = min == null ? Times.now() : min.minusSeconds(1);
            } else {
                Row m = Database.queryOne("SELECT MAX(queue_time) AS m FROM tokens WHERE service_id = ? AND status = 'WAITING' AND priority = ?",
                        t.lng("serviceId"), t.integer("priority"));
                LocalDateTime max = m == null ? null : m.time("m");
                LocalDateTime now = Times.now();
                target = (max == null || max.isBefore(now)) ? now : max.plusSeconds(1);
            }
            Database.update("UPDATE tokens SET queue_time = ?, approach_notified = 0, next_notified = 0 WHERE id = ?", target, tokenId);
            NotificationService.notify(t.lngOrNull("userId"), tokenId, "QUEUE", "Queue position updated",
                    "Token " + t.str("tokenCode") + " was moved to the " + where + " of the queue.");
            ActivityLog.log(actor, "TOKEN_MOVED", "token", tokenId, t.str("tokenCode") + " moved to the " + where);
            afterQueueChange(t.lng("serviceId"));
            return view(findToken(tokenId));
        });
    }

    /** Cancel by the customer (own tokens) or by staff / administrator. */
    public static Row cancel(Row actor, long tokenId, String reason) {
        String why = Validate.optional(reason, "Reason", 100);
        return locked(() -> {
            Row t = requireToken(tokenId);
            String role = actor.str("role");
            String status = t.str("status");
            boolean isUser = "USER".equals(role);
            if (isUser) {
                if (t.lngOrNull("userId") == null || t.lng("userId") != actor.lng("id")) {
                    throw ApiException.notFound("Token not found.");
                }
                if ("SERVING".equals(status)) {
                    throw ApiException.conflict("Your token is already being served. Please speak to the counter staff.");
                }
            } else {
                authorize(actor, t);
            }
            if (!"WAITING".equals(status) && !"CALLED".equals(status) && !"ON_HOLD".equals(status) && !"SERVING".equals(status)) {
                throw ApiException.conflict("Token " + t.str("tokenCode") + " is already " + status.toLowerCase().replace('_', ' ') + ".");
            }
            LocalDateTime now = Times.now();
            String closeReason = isUser ? "Cancelled by customer"
                    : (why != null ? why : ("ADMIN".equals(role) ? "Cancelled by administrator" : "Cancelled by staff"));
            Database.update("UPDATE tokens SET status = 'CANCELLED', ended_at = ?, close_reason = ?, service_seconds = service_seconds + ? "
                    + "WHERE id = ? AND status IN ('WAITING','CALLED','SERVING','ON_HOLD')", now, closeReason, accrued(t, now), tokenId);
            if (!isUser) {
                NotificationService.notify(t.lngOrNull("userId"), tokenId, "CANCELLED", "Token cancelled",
                        "Token " + t.str("tokenCode") + " was cancelled: " + closeReason + ".");
                ActivityLog.log(actor, "TOKEN_CANCELLED", "token", tokenId, t.str("tokenCode") + " - " + closeReason);
            }
            afterQueueChange(t.lng("serviceId"));
            return view(findToken(tokenId));
        });
    }

    // ------------------------------------------------------------------
    // Automation: assignment, notifications, background jobs
    // ------------------------------------------------------------------
    public static void afterQueueChange(long serviceId) {
        autoAssign(serviceId);
        refreshApproaching(LiveQueue.of(serviceId));
    }

    private static void autoAssign(long serviceId) {
        if (!Settings.getBool("auto_assign")) {
            return;
        }
        for (int guard = 0; guard < 50; guard++) {
            ServiceLive live = LiveQueue.of(serviceId);
            if (live == null || live.waiting.isEmpty()) {
                return;
            }
            Row free = null;
            for (Row c : live.counters) {
                if ("OPEN".equals(c.str("status")) && !c.bool("busy") && c.lngOrNull("staffId") != null) {
                    free = c;
                    break;
                }
            }
            if (free == null) {
                return;
            }
            doCall(free, live.waiting.get(0));
        }
    }

    /** "Your turn is approaching" / "You are next" notifications - each is sent only once per token. */
    private static void refreshApproaching(ServiceLive live) {
        if (live == null) {
            return;
        }
        int threshold = Settings.getInt("approach_threshold");
        for (int i = 0; i < live.waiting.size(); i++) {
            Row t = live.waiting.get(i);
            Long userId = t.lngOrNull("userId");
            if (userId == null) {
                continue;
            }
            if (i == 0 && !t.bool("nextNotified")) {
                Database.update("UPDATE tokens SET next_notified = 1, approach_notified = 1 WHERE id = ?", t.lng("id"));
                NotificationService.notify(userId, t.lng("id"), "NEXT", "You are next!",
                        "Token " + t.str("tokenCode") + ": you are next in line. Please be ready near the counters.");
            } else if (i > 0 && i <= threshold && !t.bool("approachNotified")) {
                Database.update("UPDATE tokens SET approach_notified = 1 WHERE id = ?", t.lng("id"));
                NotificationService.notify(userId, t.lng("id"), "APPROACHING", "Your turn is approaching",
                        i + (i == 1 ? " person is" : " people are") + " ahead of you (token " + t.str("tokenCode") + ").");
            }
        }
    }

    /** Background job: skip customers who were called but never reached the counter. */
    public static void autoSkipNoShows() {
        int minutes = Settings.getInt("no_show_minutes");
        LocalDateTime cutoff = Times.now().minusMinutes(minutes);
        List<Row> due = Database.query("SELECT id FROM tokens WHERE status = 'CALLED' AND last_called_at < ?", cutoff);
        for (Row d : due) {
            try {
                locked(() -> {
                    Row t = findToken(d.lng("id"));
                    if (t != null && "CALLED".equals(t.str("status")) && t.time("lastCalledAt") != null
                            && t.time("lastCalledAt").isBefore(cutoff)) {
                        markSkipped(t, "No-show: did not reach the counter within " + minutes + " min");
                        afterQueueChange(t.lng("serviceId"));
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                Log.warn("Auto-skip failed for token " + d.lng("id") + ": " + e.getMessage());
            }
        }
    }

    /** Background job: tokens from a previous day can never be served - close them. */
    public static void expireStale() {
        List<Row> stale = Database.query("SELECT id, service_id FROM tokens WHERE token_date < ? AND status IN " + ACTIVE_IN, Times.today());
        if (stale.isEmpty()) {
            return;
        }
        locked(() -> {
            LocalDateTime now = Times.now();
            for (Row s : stale) {
                Database.update("UPDATE tokens SET status = 'CANCELLED', ended_at = ?, close_reason = 'Expired at end of day' "
                        + "WHERE id = ? AND status IN ('WAITING','CALLED','SERVING','ON_HOLD')", now, s.lng("id"));
            }
            return null;
        });
        Log.info("Expired " + stale.size() + " token(s) left over from a previous day.");
    }

    /** Background job: auto-assignment and notification refresh for every service. */
    public static void refreshAll() {
        List<Row> services = Database.query("SELECT id FROM services");
        for (Row s : services) {
            long id = s.lng("id");
            locked(() -> {
                afterQueueChange(id);
                return null;
            });
        }
    }
}
