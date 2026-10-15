package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.service.LiveQueue.ServiceLive;
import com.smartqueue.util.Times;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Queue optimisation: looks at the live load of every service and at historical demand and
 * recommends counter changes ("Open Counter 4", "Move Counter 2 to Accounts" ...).
 */
public final class OptimizationService {

    private OptimizationService() {
    }

    // ------------------------------------------------------------------
    // Demand forecast from the last 28 days
    // ------------------------------------------------------------------
    /** serviceId -> [weekday 0..6][hour 0..23] = average arrivals per operating day of that weekday. */
    private static Map<Long, double[][]> historicalAverages() {
        LocalDate today = Times.today();
        LocalDateTime from = today.minusDays(28).atStartOfDay();
        LocalDateTime to = today.atStartOfDay();
        List<Row> rows = Database.query("SELECT service_id, created_at FROM tokens WHERE created_at >= ? AND created_at < ?", from, to);
        Map<Long, int[][]> counts = new HashMap<>();
        Map<Integer, Set<LocalDate>> operating = new HashMap<>();
        for (Row r : rows) {
            LocalDateTime t = r.time("createdAt");
            if (t == null) {
                continue;
            }
            int dow = t.getDayOfWeek().getValue() - 1;
            counts.computeIfAbsent(r.lng("serviceId"), k -> new int[7][24])[dow][t.getHour()]++;
            operating.computeIfAbsent(dow, k -> new HashSet<>()).add(t.toLocalDate());
        }
        Map<Long, double[][]> out = new HashMap<>();
        for (Map.Entry<Long, int[][]> e : counts.entrySet()) {
            double[][] avg = new double[7][24];
            for (int d = 0; d < 7; d++) {
                int occ = operating.containsKey(d) ? operating.get(d).size() : 0;
                for (int h = 0; h < 24; h++) {
                    avg[d][h] = occ == 0 ? 0 : e.getValue()[d][h] / (double) occ;
                }
            }
            out.put(e.getKey(), avg);
        }
        return out;
    }

    /** Expected arrivals for each of the next hours (current hour first). */
    public static Row forecast(Map<Long, ServiceLive> live, int hours) {
        Map<Long, double[][]> hist = historicalAverages();
        LocalDateTime now = Times.now();
        List<Row> list = new ArrayList<>();
        for (int i = 0; i < hours; i++) {
            LocalDateTime t = now.plusHours(i);
            int dow = t.getDayOfWeek().getValue() - 1;
            int h = t.getHour();
            double total = 0;
            List<Row> per = new ArrayList<>();
            for (ServiceLive l : live.values()) {
                double[][] a = hist.get(l.id);
                double e = a == null ? 0 : a[dow][h];
                total += e;
                per.add(new Row().with("serviceId", l.id).with("name", l.service.str("name"))
                        .with("color", l.service.str("color")).with("expected", Math.round(e * 10.0) / 10.0));
            }
            list.add(new Row().with("hour", h).with("label", AnalyticsService.windowLabel(h, 1))
                    .with("expected", Math.round(total * 10.0) / 10.0).with("perService", per));
        }
        return new Row().with("hours", list);
    }

    // ------------------------------------------------------------------
    // Recommendations
    // ------------------------------------------------------------------
    private static List<Row> idleStaff() {
        return Database.query("SELECT u.id, u.full_name FROM users u WHERE u.role = 'STAFF' AND u.is_active = 1 AND NOT EXISTS "
                + "(SELECT 1 FROM counters c WHERE c.staff_id = u.id AND c.is_deleted = 0 AND c.status IN ('OPEN','BREAK')) ORDER BY u.id");
    }

    private static Row rec(String id, String type, String severity, String title, String message, ServiceLive l, Row action, boolean canApply) {
        return new Row()
                .with("id", id)
                .with("type", type)
                .with("severity", severity)
                .with("title", title)
                .with("message", message)
                .with("serviceId", l == null ? null : (Object) l.id)
                .with("serviceName", l == null ? null : l.service.str("name"))
                .with("action", action)
                .with("canApply", canApply);
    }

    private static Row firstClosedCounter(ServiceLive l, Set<Long> used) {
        for (Row c : l.counters) {
            if ("CLOSED".equals(c.str("status")) && !used.contains(c.lng("id"))) {
                return c;
            }
        }
        return null;
    }

    private static Row findDonorCounter(Map<Long, ServiceLive> live, ServiceLive target, Set<Long> used) {
        Row best = null;
        int bestSpare = -1;
        for (ServiceLive d : live.values()) {
            if (d.id == target.id || !d.service.bool("isActive")) {
                continue;
            }
            boolean calm = d.crowdCount == 0 || (d.openCounters >= 2 && d.crowdCount <= d.openCounters);
            if (!calm) {
                continue;
            }
            for (Row c : d.counters) {
                if ("OPEN".equals(c.str("status")) && !c.bool("busy") && c.lngOrNull("staffId") != null && !used.contains(c.lng("id"))) {
                    int spare = d.openCounters * 10 - d.crowdCount;
                    if (spare > bestSpare) {
                        bestSpare = spare;
                        best = c.copy().with("donorServiceName", d.service.str("name")).with("donorCrowd", d.crowdCount);
                    }
                }
            }
        }
        return best;
    }

    private static int severityRank(String s) {
        switch (s) {
            case "HIGH":
                return 0;
            case "MEDIUM":
                return 1;
            case "LOW":
                return 2;
            default:
                return 3;
        }
    }

    public static List<Row> recommend(Map<Long, ServiceLive> live) {
        List<Row> idle = idleStaff();
        Set<Long> used = new HashSet<>();
        List<Row> out = new ArrayList<>();

        for (ServiceLive l : live.values()) {
            if (!l.service.bool("isActive")) {
                continue;
            }
            int load = l.crowdCount;
            int open = l.openCounters;
            int eta = LiveQueue.etaMinutes(l.nextJoinerMin);
            boolean needsHelp = (load > 0 && open == 0) || (open > 0 && (eta >= 15 || load >= 6 * open));
            if (!needsHelp) {
                continue;
            }
            String name = l.service.str("name");
            Row closed = firstClosedCounter(l, used);
            if (closed != null) {
                used.add(closed.lng("id"));
                String sev = (open == 0 || eta >= 25) ? "HIGH" : "MEDIUM";
                String msg = open == 0
                        ? load + (load == 1 ? " customer is" : " customers are") + " waiting for " + name + " but no counter is open."
                        : load + " people are waiting for " + name + " - the estimated wait is " + eta + " min with "
                        + open + " open counter" + (open == 1 ? "" : "s") + ".";
                boolean can = !idle.isEmpty();
                if (!can) {
                    msg += " No idle staff member is available right now.";
                }
                out.add(rec("open-" + closed.lng("id"), "OPEN_COUNTER", sev, "Open " + closed.str("name") + " (" + name + ")", msg, l,
                        new Row().with("type", "OPEN_COUNTER").with("counterId", closed.lng("id")), can));
            } else {
                Row donor = findDonorCounter(live, l, used);
                if (donor != null) {
                    used.add(donor.lng("id"));
                    out.add(rec("move-" + donor.lng("id"), "REASSIGN_COUNTER", load >= 6 ? "HIGH" : "MEDIUM",
                            "Move " + donor.str("name") + " to " + name,
                            donor.str("donorServiceName") + " is calm (" + donor.integer("donorCrowd") + " waiting) while " + name + " has "
                                    + load + " waiting and an estimated wait of " + eta + " min.", l,
                            new Row().with("type", "REASSIGN_COUNTER").with("counterId", donor.lng("id"))
                                    .with("targetServiceId", l.id), true));
                } else {
                    boolean noCounters = l.counters.isEmpty();
                    out.add(rec("add-" + l.id, "ADD_COUNTER", noCounters ? "HIGH" : "MEDIUM", "Add capacity for " + name,
                            noCounters
                                    ? load + (load == 1 ? " customer is" : " customers are") + " waiting but no counter is assigned to " + name
                                    + ". Create a counter for this service in Counters."
                                    : load + " waiting, estimated wait " + eta + " min and every counter is already in use. "
                                    + "Create a new counter in Counters or ask staff to return from break.", l, null, false));
                }
            }
        }

        // calm services with several idle open counters
        for (ServiceLive l : live.values()) {
            if (l.service.bool("isActive") && l.openCounters >= 2 && l.crowdCount == 0 && l.busyCounters == 0) {
                boolean alreadyUsed = false;
                for (Row c : l.counters) {
                    if (used.contains(c.lng("id"))) {
                        alreadyUsed = true;
                    }
                }
                if (!alreadyUsed) {
                    out.add(rec("calm-" + l.id, "INFO", "INFO", l.service.str("name") + " is quiet",
                            l.openCounters + " counters are open and nobody is waiting. One counter could take a break or help another service.",
                            l, null, false));
                }
            }
        }

        // overall crowd
        Row crowd = LiveQueue.overallCrowd(live);
        String level = crowd.str("crowdLevel");
        if (("HIGH".equals(level) || "CRITICAL".equals(level)) && !Settings.getBool("auto_assign")) {
            out.add(rec("auto-assign", "ENABLE_AUTO_ASSIGN", "MEDIUM", "Turn on automatic token assignment",
                    "The crowd is " + level + " (" + crowd.integer("queueLength") + " people). Auto-assign hands the next token to a free counter "
                            + "immediately, with no idle time between customers.", null,
                    new Row().with("type", "ENABLE_AUTO_ASSIGN"), true));
        }

        // forecast-based staffing
        Map<Long, double[][]> hist = historicalAverages();
        LocalDateTime now = Times.now();
        for (ServiceLive l : live.values()) {
            if (!l.service.bool("isActive") || l.counters.isEmpty()) {
                continue;
            }
            double[][] a = hist.get(l.id);
            if (a == null) {
                continue;
            }
            double bestExpected = 0;
            int bestHour = now.getHour();
            for (int i = 0; i < 2; i++) {
                LocalDateTime t = now.plusHours(i);
                double e = a[t.getDayOfWeek().getValue() - 1][t.getHour()];
                if (e > bestExpected) {
                    bestExpected = e;
                    bestHour = t.getHour();
                }
            }
            double capacity = l.openCounters * 60.0 / l.avgMinutes;
            Row closed = firstClosedCounter(l, used);
            if (bestExpected >= 5 && bestExpected > 0.85 * capacity && closed != null) {
                used.add(closed.lng("id"));
                boolean can = !idle.isEmpty();
                out.add(rec("forecast-" + l.id, "FORECAST", "MEDIUM", "Rush expected: " + l.service.str("name"),
                        "History shows about " + Math.round(bestExpected) + " arrivals between " + AnalyticsService.windowLabel(bestHour, 1)
                                + ", but " + l.openCounters + " open counter" + (l.openCounters == 1 ? "" : "s") + " can handle roughly "
                                + Math.round(capacity) + " per hour. Open " + closed.str("name") + " before the rush.", l,
                        new Row().with("type", "OPEN_COUNTER").with("counterId", closed.lng("id")), can));
            }
        }

        out.sort((x, y) -> Integer.compare(severityRank(x.str("severity")), severityRank(y.str("severity"))));
        return out;
    }

    // ------------------------------------------------------------------
    // Applying a recommendation
    // ------------------------------------------------------------------
    public static void apply(Row admin, String type, Long counterId, Long targetServiceId, Long staffId) {
        if (type == null) {
            throw ApiException.badRequest("Recommendation type is missing.");
        }
        switch (type) {
            case "OPEN_COUNTER": {
                if (counterId == null) {
                    throw ApiException.badRequest("Counter is missing.");
                }
                long sid;
                if (staffId != null) {
                    sid = staffId;
                } else {
                    List<Row> idle = idleStaff();
                    if (idle.isEmpty()) {
                        throw ApiException.conflict("No idle staff member is available to open this counter.");
                    }
                    sid = idle.get(0).lng("id");
                }
                QueueService.adminOpenCounter(admin, counterId, sid);
                break;
            }
            case "REASSIGN_COUNTER": {
                if (counterId == null || targetServiceId == null) {
                    throw ApiException.badRequest("Counter and target service are required.");
                }
                reassign(admin, counterId, targetServiceId);
                break;
            }
            case "ENABLE_AUTO_ASSIGN": {
                Map<String, Object> change = new HashMap<>();
                change.put("auto_assign", "true");
                Settings.update(change, admin);
                QueueService.refreshAll();
                break;
            }
            default:
                throw ApiException.badRequest("This recommendation cannot be applied automatically.");
        }
    }

    /** Moves an idle open counter to another service. */
    public static void reassign(Row admin, long counterId, long targetServiceId) {
        QueueService.locked(() -> {
            Row c = Database.queryOne("SELECT c.*, s.name AS service_name FROM counters c JOIN services s ON s.id = c.service_id "
                    + "WHERE c.id = ? AND c.is_deleted = 0", counterId);
            if (c == null) {
                throw ApiException.notFound("Counter not found.");
            }
            Row target = Database.queryOne("SELECT * FROM services WHERE id = ?", targetServiceId);
            if (target == null) {
                throw ApiException.notFound("Target service not found.");
            }
            if (c.lng("serviceId") == targetServiceId) {
                throw ApiException.conflict(c.str("name") + " already serves " + target.str("name") + ".");
            }
            if (Database.scalarLong("SELECT COUNT(*) FROM tokens WHERE counter_id = ? AND status IN ('CALLED','SERVING')",
                    counterId) > 0) {
                throw ApiException.conflict(c.str("name") + " is serving a customer right now. Wait until it is free.");
            }
            long oldService = c.lng("serviceId");
            Database.update("UPDATE counters SET service_id = ? WHERE id = ?", targetServiceId, counterId);
            if (c.lngOrNull("staffId") != null) {
                NotificationService.notify(c.lng("staffId"), null, "COUNTER", "Counter reassigned",
                        c.str("name") + " now serves " + target.str("name") + " (was " + c.str("serviceName") + ").");
            }
            ActivityLog.log(admin, "COUNTER_REASSIGNED", "counter", counterId,
                    c.str("name") + ": " + c.str("serviceName") + " -> " + target.str("name"));
            QueueService.afterQueueChange(oldService);
            QueueService.afterQueueChange(targetServiceId);
            return null;
        });
    }
}
