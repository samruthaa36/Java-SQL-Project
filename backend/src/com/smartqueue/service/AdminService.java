package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.service.LiveQueue.ServiceLive;
import com.smartqueue.util.Passwords;
import com.smartqueue.util.Times;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Administrator features: dashboard, live queues, user / service / counter management, demo tools. */
public final class AdminService {

    private static final Random RANDOM = new Random();

    private AdminService() {
    }

    // ==================================================================
    // Dashboard
    // ==================================================================
    private static int bestWindowStart(int[] counts, int len) {
        int best = 0;
        int bestSum = -1;
        for (int h = 0; h + len <= 24; h++) {
            int sum = 0;
            for (int i = 0; i < len; i++) {
                sum += counts[h + i];
            }
            if (sum > bestSum) {
                bestSum = sum;
                best = h;
            }
        }
        return best;
    }

    public static Row dashboard() {
        LocalDateTime now = Times.now();
        LocalDateTime start = Times.today().atStartOfDay();
        Map<Long, ServiceLive> live = LiveQueue.all();
        List<Row> tokens = Database.query("SELECT id, service_id, status, created_at, called_at, ended_at, service_seconds, counter_id "
                + "FROM tokens WHERE created_at >= ?", start);

        int total = 0;
        int completed = 0;
        int cancelled = 0;
        int skipped = 0;
        double waitSum = 0;
        int waitN = 0;
        double svcSum = 0;
        int svcN = 0;
        int[] hourly = new int[24];
        Map<Long, Integer> totalByService = new HashMap<>();
        Map<Long, Integer> servedByService = new HashMap<>();
        Map<Long, Integer> servedByCounter = new HashMap<>();
        for (Row t : tokens) {
            total++;
            LocalDateTime created = t.time("createdAt");
            if (created != null) {
                hourly[created.getHour()]++;
            }
            totalByService.merge(t.lng("serviceId"), 1, Integer::sum);
            String st = t.str("status");
            if ("COMPLETED".equals(st)) {
                completed++;
                servedByService.merge(t.lng("serviceId"), 1, Integer::sum);
                if (t.lngOrNull("counterId") != null) {
                    servedByCounter.merge(t.lng("counterId"), 1, Integer::sum);
                }
                if (t.integer("serviceSeconds") > 0) {
                    svcSum += t.dbl("serviceSeconds") / 60.0;
                    svcN++;
                }
            } else if ("CANCELLED".equals(st)) {
                cancelled++;
            } else if ("SKIPPED".equals(st)) {
                skipped++;
            }
            if (created != null && t.time("calledAt") != null) {
                waitSum += Math.max(0, Times.minutes(created, t.time("calledAt")));
                waitN++;
            }
        }

        Row crowd = LiveQueue.overallCrowd(live);

        // peak window: today's own data once there is enough of it, otherwise the last 14 days
        int[] basisCounts = hourly;
        String basis = "today";
        if (total < 15) {
            int[] hist = new int[24];
            for (Row r : Database.query("SELECT created_at FROM tokens WHERE created_at >= ? AND created_at < ?",
                    start.minusDays(14), start)) {
                LocalDateTime c = r.time("createdAt");
                if (c != null) {
                    hist[c.getHour()]++;
                }
            }
            basisCounts = hist;
            basis = "history";
        }
        int peakStart = bestWindowStart(basisCounts, 2);
        boolean anyData = false;
        for (int c : basisCounts) {
            if (c > 0) {
                anyData = true;
            }
        }

        Row cards = new Row();
        cards.put("totalTokens", total);
        cards.put("completed", completed);
        cards.put("cancelled", cancelled);
        cards.put("skipped", skipped);
        cards.put("waiting", crowd.integer("waiting"));
        cards.put("called", crowd.integer("called"));
        cards.put("serving", crowd.integer("serving"));
        cards.put("onHold", crowd.integer("onHold"));
        cards.put("avgWaitMinutes", waitN == 0 ? 0 : Math.round(waitSum / waitN * 10.0) / 10.0);
        cards.put("avgServiceMinutes", svcN == 0 ? 0 : Math.round(svcSum / svcN * 10.0) / 10.0);
        cards.put("completionRate", total == 0 ? 0 : Math.round(completed * 1000.0 / total) / 10.0);
        cards.put("peakWindow", anyData ? AnalyticsService.windowLabel(peakStart, 2) : null);
        cards.put("peakBasis", basis);

        List<Row> services = new ArrayList<>();
        for (ServiceLive l : live.values()) {
            Row s = LiveQueue.summary(l);
            s.put("todayTotal", totalByService.getOrDefault(l.id, 0));
            s.put("servedToday", servedByService.getOrDefault(l.id, 0));
            services.add(s);
        }

        List<Row> counters = new ArrayList<>();
        for (ServiceLive l : live.values()) {
            for (Row c : l.counters) {
                counters.add(new Row()
                        .with("id", c.lng("id"))
                        .with("name", c.str("name"))
                        .with("status", c.str("status"))
                        .with("serviceId", l.id)
                        .with("serviceName", l.service.str("name"))
                        .with("serviceColor", l.service.str("color"))
                        .with("staffId", c.lngOrNull("staffId"))
                        .with("staffName", c.str("staffName"))
                        .with("openedAt", c.time("openedAt"))
                        .with("busy", c.bool("busy"))
                        .with("currentTokenId", c.get("currentTokenId"))
                        .with("currentCode", c.str("currentCode"))
                        .with("currentStatus", c.str("currentStatus"))
                        .with("currentSince", c.time("currentSince"))
                        .with("servedToday", servedByCounter.getOrDefault(c.lng("id"), 0)));
            }
        }

        Row out = new Row();
        out.put("generatedAt", now);
        out.put("cards", cards);
        out.put("crowd", crowd);
        out.put("services", services);
        out.put("counters", counters);
        out.put("hourlyToday", hourly);
        out.put("forecast", OptimizationService.forecast(live, 4));
        out.put("recommendations", OptimizationService.recommend(live));
        out.put("idleStaff", Database.scalarLong("SELECT COUNT(*) FROM users u WHERE u.role = 'STAFF' AND u.is_active = 1 AND NOT EXISTS "
                + "(SELECT 1 FROM counters c WHERE c.staff_id = u.id AND c.is_deleted = 0 AND c.status IN ('OPEN','BREAK'))"));
        return out;
    }

    // ==================================================================
    // Live queues (all services)
    // ==================================================================
    public static Row liveQueues() {
        LocalDateTime now = Times.now();
        Map<Long, ServiceLive> live = LiveQueue.all();
        List<Row> list = new ArrayList<>();
        for (ServiceLive l : live.values()) {
            Row s = LiveQueue.summary(l);
            List<Row> waiting = new ArrayList<>();
            for (int i = 0; i < l.waiting.size(); i++) {
                Row t = l.waiting.get(i);
                LocalDateTime created = t.time("createdAt");
                waiting.add(new Row()
                        .with("id", t.lng("id"))
                        .with("tokenCode", t.str("tokenCode"))
                        .with("customerName", t.str("customerName"))
                        .with("priority", t.integer("priority"))
                        .with("priorityReason", t.str("priorityReason"))
                        .with("source", t.str("source"))
                        .with("position", i + 1)
                        .with("createdAt", created)
                        .with("waitedMinutes", created == null ? 0 : Math.max(0, Math.round(Times.minutes(created, now))))
                        .with("etaMinutes", LiveQueue.etaMinutes(l.startMin[i])));
            }
            List<Row> hold = new ArrayList<>();
            for (Row t : l.onHold) {
                hold.add(new Row().with("id", t.lng("id")).with("tokenCode", t.str("tokenCode"))
                        .with("customerName", t.str("customerName")).with("priority", t.integer("priority")));
            }
            List<Row> counters = new ArrayList<>();
            for (Row c : l.counters) {
                counters.add(new Row()
                        .with("id", c.lng("id"))
                        .with("name", c.str("name"))
                        .with("status", c.str("status"))
                        .with("staffName", c.str("staffName"))
                        .with("currentTokenId", c.get("currentTokenId"))
                        .with("currentCode", c.str("currentCode"))
                        .with("currentStatus", c.str("currentStatus"))
                        .with("currentCustomer", c.str("currentCustomer")));
            }
            s.put("queue", waiting);
            s.put("onHoldTokens", hold);
            s.put("counters", counters);
            list.add(s);
        }
        return new Row().with("services", list).with("crowd", LiveQueue.overallCrowd(live)).with("generatedAt", now);
    }

    // ==================================================================
    // Users
    // ==================================================================
    public static Row listUsers(String search, String role, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (search != null) {
            String like = "%" + search.replace("%", "").replace("_", "").toLowerCase() + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(u.phone) LIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (role != null) {
            String r = role.toUpperCase();
            if (!r.equals("USER") && !r.equals("STAFF") && !r.equals("ADMIN")) {
                throw ApiException.badRequest("Unknown role filter.");
            }
            where.append(" AND u.role = ?");
            params.add(r);
        }
        long total = Database.scalarLong("SELECT COUNT(*) FROM users u" + where, params.toArray());
        List<Row> rows = Database.queryPage("SELECT u.id, u.full_name, u.email, u.phone, u.role, u.is_priority, u.priority_reason, u.is_active, "
                + "u.created_at, u.last_login_at, "
                + "(SELECT COUNT(*) FROM tokens t WHERE t.user_id = u.id) AS visits, "
                + "(SELECT COUNT(*) FROM tokens t WHERE t.served_by = u.id AND t.status = 'COMPLETED') AS served, "
                + "(SELECT MIN(c.name) FROM counters c WHERE c.staff_id = u.id AND c.is_deleted = 0) AS counter_name "
                + "FROM users u" + where + " ORDER BY u.id DESC", Math.max(0, (page - 1) * size), size, params.toArray());
        Row out = new Row();
        out.put("items", rows);
        out.put("total", total);
        out.put("page", page);
        out.put("pageSize", size);
        out.put("pages", Math.max(1, (total + size - 1) / size));
        out.put("counts", new Row()
                .with("users", Database.scalarLong("SELECT COUNT(*) FROM users WHERE role = 'USER'"))
                .with("staff", Database.scalarLong("SELECT COUNT(*) FROM users WHERE role = 'STAFF'"))
                .with("admins", Database.scalarLong("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'")));
        return out;
    }

    private static String roleOf(String raw) {
        String r = raw == null ? "USER" : raw.trim().toUpperCase();
        if (!r.equals("USER") && !r.equals("STAFF") && !r.equals("ADMIN")) {
            throw ApiException.badRequest("Role must be USER, STAFF or ADMIN.");
        }
        return r;
    }

    public static Row createUser(Row admin, String fullName, String email, String phone, String password, String role,
                                 boolean priority, String priorityReason) {
        String name = Validate.text(fullName, "Full name", 2, 100);
        String mail = Validate.email(email);
        String ph = Validate.phone(phone);
        String r = roleOf(role);
        String temp = null;
        String pw = password;
        if (pw == null || pw.trim().isEmpty()) {
            temp = Passwords.temporaryPassword();
            pw = temp;
        } else {
            Validate.password(pw);
        }
        String reason = priority ? Validate.optional(priorityReason, "Priority reason", 100) : null;
        if (priority && reason == null) {
            reason = "Priority customer";
        }
        Row created = AuthService.createUser(name, mail, ph, pw, r, priority && "USER".equals(r), reason, admin);
        if (temp != null) {
            created.put("temporaryPassword", temp);
        }
        return created;
    }

    private static long otherActiveAdmins(long exceptId) {
        return Database.scalarLong("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND is_active = 1 AND id <> ?", exceptId);
    }

    public static Row updateUser(Row admin, long id, String fullName, String email, String phone, String role, Boolean active,
                                 Boolean priority, String priorityReason) {
        return QueueService.locked(() -> {
            Row u = Database.queryOne("SELECT * FROM users WHERE id = ?", id);
            if (u == null) {
                throw ApiException.notFound("User not found.");
            }
            boolean self = id == admin.lng("id");
            String newName = fullName == null ? u.str("fullName") : Validate.text(fullName, "Full name", 2, 100);
            String newEmail = email == null ? u.str("email") : Validate.email(email);
            String newPhone = phone == null ? u.str("phone") : Validate.phone(phone);
            String newRole = role == null ? u.str("role") : roleOf(role);
            boolean newActive = active == null ? u.bool("isActive") : active;
            boolean newPriority = priority == null ? u.bool("isPriority") : priority;
            String newReason = priorityReason == null ? u.str("priorityReason") : Validate.optional(priorityReason, "Priority reason", 100);
            if (!"USER".equals(newRole)) {
                newPriority = false;
            }
            if (newPriority && newReason == null) {
                newReason = "Priority customer";
            }
            if (!newPriority) {
                newReason = null;
            }
            if (!newEmail.equals(u.str("email"))
                    && Database.scalarLong("SELECT COUNT(*) FROM users WHERE email = ? AND id <> ?", newEmail, id) > 0) {
                throw ApiException.conflict("Another account already uses this email.");
            }
            if (self && (!newRole.equals(u.str("role")) || !newActive)) {
                throw ApiException.conflict("You cannot change your own role or deactivate your own account.");
            }
            boolean wasAdmin = "ADMIN".equals(u.str("role")) && u.bool("isActive");
            boolean stillAdmin = "ADMIN".equals(newRole) && newActive;
            if (wasAdmin && !stillAdmin && otherActiveAdmins(id) == 0) {
                throw ApiException.conflict("There must be at least one active administrator.");
            }
            // a staff member who leaves the STAFF role or is deactivated must not keep a counter
            boolean leavingStaff = "STAFF".equals(u.str("role")) && (!"STAFF".equals(newRole) || !newActive);
            if (leavingStaff) {
                Row counter = QueueService.counterOfStaff(id);
                if (counter != null) {
                    QueueService.adminCloseCounter(admin, counter.lng("id"));
                }
            }
            Database.update("UPDATE users SET full_name = ?, email = ?, phone = ?, role = ?, is_active = ?, is_priority = ?, priority_reason = ? "
                    + "WHERE id = ?", newName, newEmail, newPhone, newRole, newActive ? 1 : 0, newPriority ? 1 : 0, newReason, id);
            if (!newActive || !newRole.equals(u.str("role"))) {
                Database.update("DELETE FROM sessions WHERE user_id = ?", id);
            }
            ActivityLog.log(admin, "USER_UPDATED", "user", id, newEmail + " (" + newRole + (newActive ? "" : ", deactivated") + ")");
            return AuthService.publicUser(Database.queryOne("SELECT * FROM users WHERE id = ?", id));
        });
    }

    public static String resetPassword(Row admin, long id) {
        Row u = Database.queryOne("SELECT * FROM users WHERE id = ?", id);
        if (u == null) {
            throw ApiException.notFound("User not found.");
        }
        String temp = Passwords.temporaryPassword();
        Database.update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(temp), id);
        Database.update("DELETE FROM sessions WHERE user_id = ?", id);
        ActivityLog.log(admin, "PASSWORD_RESET", "user", id, "Password reset for " + u.str("email"));
        return temp;
    }

    public static void deleteUser(Row admin, long id) {
        QueueService.locked(() -> {
            Row u = Database.queryOne("SELECT * FROM users WHERE id = ?", id);
            if (u == null) {
                throw ApiException.notFound("User not found.");
            }
            if (id == admin.lng("id")) {
                throw ApiException.conflict("You cannot delete your own account.");
            }
            if ("ADMIN".equals(u.str("role")) && u.bool("isActive") && otherActiveAdmins(id) == 0) {
                throw ApiException.conflict("There must be at least one active administrator.");
            }
            long history = Database.scalarLong("SELECT COUNT(*) FROM tokens WHERE user_id = ? OR issued_by = ? OR served_by = ?", id, id, id);
            if (history > 0) {
                throw ApiException.conflict("This user has queue history and cannot be deleted. Deactivate the account instead.");
            }
            Row counter = QueueService.counterOfStaff(id);
            if (counter != null) {
                QueueService.adminCloseCounter(admin, counter.lng("id"));
            }
            Database.update("DELETE FROM users WHERE id = ?", id);
            ActivityLog.log(admin, "USER_DELETED", "user", id, "Deleted " + u.str("email"));
            return null;
        });
    }

    // ==================================================================
    // Services
    // ==================================================================
    public static List<Row> listServices() {
        Map<Long, ServiceLive> live = LiveQueue.all();
        Map<Long, Long> tokenCounts = new HashMap<>();
        for (Row r : Database.query("SELECT service_id, COUNT(*) AS cnt FROM tokens GROUP BY service_id")) {
            tokenCounts.put(r.lng("serviceId"), r.lng("cnt"));
        }
        List<Row> out = new ArrayList<>();
        for (ServiceLive l : live.values()) {
            Row s = LiveQueue.summary(l);
            s.put("totalTokens", tokenCounts.getOrDefault(l.id, 0L));
            out.add(s);
        }
        return out;
    }

    private static double minutesOf(Double v) {
        if (v == null) {
            throw ApiException.badRequest("Average service time is required.");
        }
        if (v < 0.5 || v > 180) {
            throw ApiException.badRequest("Average service time must be between 0.5 and 180 minutes.");
        }
        return Math.round(v * 100.0) / 100.0;
    }

    public static Row createService(Row admin, String name, String prefix, String description, String icon, String color,
                                    Double avgMinutes, Boolean active) {
        String n = Validate.text(name, "Service name", 2, 80);
        String p = Validate.prefix(prefix);
        String d = Validate.optional(description, "Description", 255);
        String ic = Validate.icon(icon);
        String col = Validate.color(color);
        double avg = minutesOf(avgMinutes);
        if (Database.scalarLong("SELECT COUNT(*) FROM services WHERE LOWER(name) = LOWER(?)", n) > 0) {
            throw ApiException.conflict("A service with this name already exists.");
        }
        if (Database.scalarLong("SELECT COUNT(*) FROM services WHERE prefix = ?", p) > 0) {
            throw ApiException.conflict("The prefix " + p + " is already used by another service.");
        }
        long id = Database.insert("INSERT INTO services (id, name, prefix, description, icon, color, avg_service_minutes, is_active, created_at) "
                + "VALUES (services_seq.NEXTVAL, ?,?,?,?,?,?,?,?)", n, p, d, ic, col, avg, active == null || active ? 1 : 0, Times.now());
        ActivityLog.log(admin, "SERVICE_CREATED", "service", id, n + " (" + p + ")");
        return LiveQueue.summary(LiveQueue.of(id));
    }

    public static Row updateService(Row admin, long id, String name, String prefix, String description, String icon, String color,
                                    Double avgMinutes, Boolean active) {
        return QueueService.locked(() -> {
            Row s = Database.queryOne("SELECT * FROM services WHERE id = ?", id);
            if (s == null) {
                throw ApiException.notFound("Service not found.");
            }
            String n = name == null ? s.str("name") : Validate.text(name, "Service name", 2, 80);
            String p = prefix == null ? s.str("prefix") : Validate.prefix(prefix);
            String d = description == null ? s.str("description") : Validate.optional(description, "Description", 255);
            String ic = icon == null ? s.str("icon") : Validate.icon(icon);
            String col = color == null ? s.str("color") : Validate.color(color);
            double avg = avgMinutes == null ? s.dbl("avgServiceMinutes") : minutesOf(avgMinutes);
            boolean act = active == null ? s.bool("isActive") : active;
            if (Database.scalarLong("SELECT COUNT(*) FROM services WHERE LOWER(name) = LOWER(?) AND id <> ?", n, id) > 0) {
                throw ApiException.conflict("A service with this name already exists.");
            }
            if (!p.equals(s.str("prefix"))) {
                if (Database.scalarLong("SELECT COUNT(*) FROM services WHERE prefix = ? AND id <> ?", p, id) > 0) {
                    throw ApiException.conflict("The prefix " + p + " is already used by another service.");
                }
                if (Database.scalarLong("SELECT COUNT(*) FROM tokens WHERE service_id = ?", id) > 0) {
                    throw ApiException.conflict("The prefix cannot be changed after tokens have been issued for this service.");
                }
            }
            Database.update("UPDATE services SET name = ?, prefix = ?, description = ?, icon = ?, color = ?, avg_service_minutes = ?, "
                    + "is_active = ? WHERE id = ?", n, p, d, ic, col, avg, act ? 1 : 0, id);
            ActivityLog.log(admin, "SERVICE_UPDATED", "service", id, n + (act ? "" : " (disabled)"));
            return LiveQueue.summary(LiveQueue.of(id));
        });
    }

    public static void deleteService(Row admin, long id) {
        QueueService.locked(() -> {
            Row s = Database.queryOne("SELECT * FROM services WHERE id = ?", id);
            if (s == null) {
                throw ApiException.notFound("Service not found.");
            }
            if (Database.scalarLong("SELECT COUNT(*) FROM tokens WHERE service_id = ?", id) > 0) {
                throw ApiException.conflict("This service has token history and cannot be deleted. Disable it instead.");
            }
            if (Database.scalarLong("SELECT COUNT(*) FROM counters WHERE service_id = ? AND is_deleted = 0", id) > 0) {
                throw ApiException.conflict("Move or delete the counters of this service first.");
            }
            Database.update("DELETE FROM counters WHERE service_id = ?", id);
            Database.update("DELETE FROM services WHERE id = ?", id);
            ActivityLog.log(admin, "SERVICE_DELETED", "service", id, "Deleted " + s.str("name"));
            return null;
        });
    }

    // ==================================================================
    // Counters
    // ==================================================================
    public static List<Row> listCounters() {
        List<Row> out = new ArrayList<>();
        Map<Long, ServiceLive> live = LiveQueue.all();
        Map<Long, Long> served = new HashMap<>();
        for (Row r : Database.query("SELECT counter_id, COUNT(*) AS cnt FROM tokens WHERE status = 'COMPLETED' AND ended_at >= ? "
                + "AND counter_id IS NOT NULL GROUP BY counter_id", Times.today().atStartOfDay())) {
            served.put(r.lng("counterId"), r.lng("cnt"));
        }
        for (ServiceLive l : live.values()) {
            for (Row c : l.counters) {
                out.add(new Row()
                        .with("id", c.lng("id"))
                        .with("name", c.str("name"))
                        .with("status", c.str("status"))
                        .with("serviceId", l.id)
                        .with("serviceName", l.service.str("name"))
                        .with("serviceColor", l.service.str("color"))
                        .with("staffId", c.lngOrNull("staffId"))
                        .with("staffName", c.str("staffName"))
                        .with("openedAt", c.time("openedAt"))
                        .with("busy", c.bool("busy"))
                        .with("currentCode", c.str("currentCode"))
                        .with("servedToday", served.getOrDefault(c.lng("id"), 0L)));
            }
        }
        out.sort((a, b) -> Long.compare(a.lng("id"), b.lng("id")));
        return out;
    }

    public static Row createCounter(Row admin, String name, long serviceId) {
        String n = Validate.text(name, "Counter name", 1, 60);
        return QueueService.locked(() -> {
            if (Database.scalarLong("SELECT COUNT(*) FROM services WHERE id = ?", serviceId) == 0) {
                throw ApiException.notFound("Service not found.");
            }
            if (Database.scalarLong("SELECT COUNT(*) FROM counters WHERE LOWER(name) = LOWER(?) AND is_deleted = 0", n) > 0) {
                throw ApiException.conflict("A counter with this name already exists.");
            }
            long id = Database.insert("INSERT INTO counters (id, name, service_id, status, is_deleted, created_at) VALUES (counters_seq.NEXTVAL, ?,?,'CLOSED',0,?)",
                    n, serviceId, Times.now());
            ActivityLog.log(admin, "COUNTER_CREATED", "counter", id, n);
            return Database.queryOne("SELECT * FROM counters WHERE id = ?", id);
        });
    }

    public static Row updateCounter(Row admin, long id, String name, Long serviceId) {
        return QueueService.locked(() -> {
            Row c = Database.queryOne("SELECT * FROM counters WHERE id = ? AND is_deleted = 0", id);
            if (c == null) {
                throw ApiException.notFound("Counter not found.");
            }
            String n = name == null ? c.str("name") : Validate.text(name, "Counter name", 1, 60);
            long sid = serviceId == null ? c.lng("serviceId") : serviceId;
            if (Database.scalarLong("SELECT COUNT(*) FROM counters WHERE LOWER(name) = LOWER(?) AND is_deleted = 0 AND id <> ?", n, id) > 0) {
                throw ApiException.conflict("A counter with this name already exists.");
            }
            if (sid != c.lng("serviceId")) {
                if (Database.scalarLong("SELECT COUNT(*) FROM services WHERE id = ?", sid) == 0) {
                    throw ApiException.notFound("Service not found.");
                }
                if (!"CLOSED".equals(c.str("status"))) {
                    throw ApiException.conflict("Close the counter first, or use 'Reassign' for an idle open counter.");
                }
            }
            Database.update("UPDATE counters SET name = ?, service_id = ? WHERE id = ?", n, sid, id);
            ActivityLog.log(admin, "COUNTER_UPDATED", "counter", id, n);
            return Database.queryOne("SELECT * FROM counters WHERE id = ?", id);
        });
    }

    public static void deleteCounter(Row admin, long id) {
        QueueService.locked(() -> {
            Row c = Database.queryOne("SELECT * FROM counters WHERE id = ? AND is_deleted = 0", id);
            if (c == null) {
                throw ApiException.notFound("Counter not found.");
            }
            if (!"CLOSED".equals(c.str("status"))) {
                throw ApiException.conflict("Close the counter before removing it.");
            }
            Database.update("UPDATE counters SET is_deleted = 1 WHERE id = ?", id);
            ActivityLog.log(admin, "COUNTER_DELETED", "counter", id, "Removed " + c.str("name"));
            return null;
        });
    }

    // ==================================================================
    // Demo tools (for presentations)
    // ==================================================================
    private static final String[] DEMO_NAMES = {"Aarav Sharma", "Diya Patel", "Vihaan Reddy", "Ananya Iyer", "Rohan Gupta", "Meera Nair",
            "Kabir Singh", "Isha Menon", "Arjun Das", "Sneha Rao", "Rahul Verma", "Pooja Joshi", "Karan Mehta", "Nisha Kapoor",
            "Siddharth Bose", "Lakshmi Pillai", "Manish Kumar", "Tara Banerjee", "Varun Chopra", "Aditi Desai"};

    /** Adds random walk-in customers to the queues so crowd levels and recommendations can be demonstrated. */
    public static Row simulateCrowd(Row admin, int count) {
        if (count < 1 || count > 60) {
            throw ApiException.badRequest("Choose between 1 and 60 customers.");
        }
        List<Row> services = Database.query("SELECT id, name FROM services WHERE is_active = 1 ORDER BY id");
        if (services.isEmpty()) {
            throw ApiException.conflict("There is no active service.");
        }
        int created = 0;
        for (int i = 0; i < count; i++) {
            Row s = services.get(RANDOM.nextInt(services.size()));
            int priority = RANDOM.nextInt(12) == 0 ? 1 : 0;
            try {
                QueueService.issueWalkIn(null, s.lng("id"), DEMO_NAMES[RANDOM.nextInt(DEMO_NAMES.length)], null, priority,
                        priority > 0 ? "Senior citizen" : null, "Demo traffic");
                created++;
            } catch (ApiException e) {
                // queue full - stop quietly
                break;
            }
        }
        ActivityLog.log(admin, "DEMO_CROWD", "token", null, "Simulated " + created + " walk-in customers");
        return new Row().with("created", created);
    }

    /** Cancels everyone who is waiting (used to reset a demo). */
    public static Row clearWaiting(Row admin) {
        return QueueService.locked(() -> {
            List<Row> waiting = Database.query("SELECT id, user_id, token_code, service_id FROM tokens WHERE status = 'WAITING'");
            LocalDateTime now = Times.now();
            for (Row t : waiting) {
                Database.update("UPDATE tokens SET status = 'CANCELLED', ended_at = ?, close_reason = 'Queue cleared by administrator' "
                        + "WHERE id = ? AND status = 'WAITING'", now, t.lng("id"));
                NotificationService.notify(t.lngOrNull("userId"), t.lng("id"), "CANCELLED", "Token cancelled",
                        "Token " + t.str("tokenCode") + " was cancelled because the queue was cleared by an administrator.");
            }
            ActivityLog.log(admin, "QUEUE_CLEARED", "token", null, "Cancelled " + waiting.size() + " waiting token(s)");
            return new Row().with("cancelled", waiting.size());
        });
    }

    static LocalDate today() {
        return Times.today();
    }
}
