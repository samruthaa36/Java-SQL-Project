package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.util.Times;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "smart engine": builds a live picture of every service queue.
 *
 *  - Serving order  : emergency first, then priority/normal interleaved with an
 *                     anti-starvation rule (after N priority tokens in a row one normal token is served).
 *  - Waiting time   : discrete simulation of the open counters. Each counter becomes free after the
 *                     remaining time of its current customer, and every following customer is handed to
 *                     the counter that frees up first. Service time = blend of the configured default and
 *                     the real average of the last 14 days (it gets more accurate as data is collected).
 *  - Crowd level    : queue length compared with the admin-configured limits.
 */
public final class LiveQueue {

    private LiveQueue() {
    }

    public static final class ServiceLive {
        public Row service;
        public long id;
        public double avgMinutes;
        public int sampleSize;
        public final List<Row> counters = new ArrayList<>();
        public int openCounters;
        public int busyCounters;
        public int breakCounters;
        /** Waiting tokens in the exact order they will be called. */
        public final List<Row> waiting = new ArrayList<>();
        public final List<Row> onHold = new ArrayList<>();
        public final Map<Long, Integer> indexById = new HashMap<>();
        /** Predicted start of service (minutes from now) for each waiting token; last entry = next joiner. */
        public double[] startMin = new double[1];
        public double nextJoinerMin;
        public int calledCount;
        public int servingCount;
        public boolean noCounters;
        public int crowdCount;
        public String crowdLevel = "LOW";
    }

    // ------------------------------------------------------------------
    // Crowd level
    // ------------------------------------------------------------------
    public static String crowdLevel(int count) {
        if (count <= Settings.getInt("crowd_low_max")) {
            return "LOW";
        }
        if (count <= Settings.getInt("crowd_moderate_max")) {
            return "MODERATE";
        }
        if (count <= Settings.getInt("crowd_high_max")) {
            return "HIGH";
        }
        return "CRITICAL";
    }

    public static int etaMinutes(double startMin) {
        return startMin < 0.5 ? 0 : (int) Math.ceil(startMin);
    }

    // ------------------------------------------------------------------
    // Serving order with anti-starvation
    // ------------------------------------------------------------------

    /** @param sorted waiting tokens sorted by priority DESC, queue_time ASC, id ASC */
    static List<Row> order(List<Row> sorted, int streakStart, int burst) {
        List<Row> emergency = new ArrayList<>();
        List<Row> priority = new ArrayList<>();
        List<Row> normal = new ArrayList<>();
        for (Row t : sorted) {
            int p = t.integer("priority");
            if (p >= 2) {
                emergency.add(t);
            } else if (p == 1) {
                priority.add(t);
            } else {
                normal.add(t);
            }
        }
        List<Row> out = new ArrayList<>(sorted.size());
        out.addAll(emergency);
        int streak = streakStart;
        int pi = 0;
        int ni = 0;
        while (pi < priority.size() || ni < normal.size()) {
            boolean takePriority;
            if (pi >= priority.size()) {
                takePriority = false;
            } else if (ni >= normal.size()) {
                takePriority = true;
            } else {
                takePriority = streak < burst;
            }
            if (takePriority) {
                out.add(priority.get(pi++));
                streak++;
            } else {
                out.add(normal.get(ni++));
                streak = 0;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Snapshot of all services
    // ------------------------------------------------------------------
    private static final String ACTIVE_SQL =
            "SELECT t.id, t.service_id, t.token_code, t.status, t.priority, t.priority_reason, t.counter_id, t.customer_name, "
                    + "t.customer_phone, t.user_id, t.source, t.notes, t.queue_time, t.created_at, t.called_at, t.last_called_at, "
                    + "t.serving_at, t.estimated_wait_min, t.approach_notified, t.next_notified, t.call_count "
                    + "FROM tokens t WHERE t.status IN ('WAITING','CALLED','SERVING','ON_HOLD') "
                    + "ORDER BY t.priority DESC, t.queue_time ASC, t.id ASC";

    public static Map<Long, ServiceLive> all() {
        LocalDateTime now = Times.now();
        List<Row> services = Database.query("SELECT * FROM services ORDER BY id");
        List<Row> counters = Database.query("SELECT c.*, u.full_name AS staff_name FROM counters c "
                + "LEFT JOIN users u ON u.id = c.staff_id WHERE c.is_deleted = 0 ORDER BY c.id");
        List<Row> active = Database.query(ACTIVE_SQL);
        List<Row> stats = Database.query("SELECT service_id, AVG(service_seconds) AS avg_sec, COUNT(*) AS cnt FROM tokens "
                + "WHERE status = 'COMPLETED' AND service_seconds > 0 AND ended_at >= ? GROUP BY service_id", now.minusDays(14));
        Map<Long, double[]> statMap = new HashMap<>();
        for (Row s : stats) {
            statMap.put(s.lng("serviceId"), new double[]{s.dbl("avgSec"), s.dbl("cnt")});
        }

        int burst = Settings.getInt("priority_burst");
        Map<Long, ServiceLive> out = new LinkedHashMap<>();
        Map<Long, List<Row>> waitingRaw = new HashMap<>();
        for (Row s : services) {
            ServiceLive l = new ServiceLive();
            l.service = s;
            l.id = s.lng("id");
            out.put(l.id, l);
            waitingRaw.put(l.id, new ArrayList<>());
        }

        Map<Long, Row> currentByCounter = new HashMap<>();
        for (Row t : active) {
            ServiceLive l = out.get(t.lng("serviceId"));
            if (l == null) {
                continue;
            }
            String st = t.str("status");
            if ("WAITING".equals(st)) {
                waitingRaw.get(l.id).add(t);
            } else if ("CALLED".equals(st)) {
                l.calledCount++;
                currentByCounter.put(t.lng("counterId"), t);
            } else if ("SERVING".equals(st)) {
                l.servingCount++;
                currentByCounter.put(t.lng("counterId"), t);
            } else if ("ON_HOLD".equals(st)) {
                l.onHold.add(t);
            }
        }

        for (Row c : counters) {
            ServiceLive l = out.get(c.lng("serviceId"));
            if (l == null) {
                continue;
            }
            Row cr = c.copy();
            Row cur = currentByCounter.get(c.lng("id"));
            cr.put("busy", cur != null);
            cr.put("currentTokenId", cur == null ? null : cur.lng("id"));
            cr.put("currentCode", cur == null ? null : cur.str("tokenCode"));
            cr.put("currentStatus", cur == null ? null : cur.str("status"));
            cr.put("currentCustomer", cur == null ? null : cur.str("customerName"));
            if (cur != null) {
                LocalDateTime since = "SERVING".equals(cur.str("status")) ? cur.time("servingAt") : cur.time("lastCalledAt");
                cr.put("currentSince", since);
            } else {
                cr.put("currentSince", null);
            }
            l.counters.add(cr);
            String status = c.str("status");
            if ("OPEN".equals(status)) {
                l.openCounters++;
                if (cur != null) {
                    l.busyCounters++;
                }
            } else if ("BREAK".equals(status)) {
                l.breakCounters++;
            }
        }

        for (ServiceLive l : out.values()) {
            // average service time: default blended with real data of the last 14 days
            double def = l.service.dbl("avgServiceMinutes");
            double avg = def;
            double[] st = statMap.get(l.id);
            if (st != null && st[1] > 0) {
                double n = st[1];
                double hist = st[0] / 60.0;
                double w = n / (n + 10.0);
                avg = w * hist + (1 - w) * def;
                l.sampleSize = (int) n;
            }
            l.avgMinutes = Math.max(0.5, Math.min(180.0, avg));

            // serving order
            List<Row> ordered = order(waitingRaw.get(l.id), l.service.integer("priorityStreak"), burst);
            l.waiting.addAll(ordered);
            for (int i = 0; i < ordered.size(); i++) {
                l.indexById.put(ordered.get(i).lng("id"), i);
            }

            // waiting-time simulation over the open counters
            List<Double> avail = new ArrayList<>();
            for (Row c : l.counters) {
                if (!"OPEN".equals(c.str("status"))) {
                    continue;
                }
                double a = 0.0;
                if (c.bool("busy")) {
                    LocalDateTime since = c.time("currentSince");
                    if ("SERVING".equals(c.str("currentStatus")) && since != null) {
                        double elapsed = Math.max(0.0, Times.minutes(since, now));
                        a = Math.max(0.3 * l.avgMinutes, l.avgMinutes - elapsed);
                    } else {
                        a = l.avgMinutes;
                    }
                }
                avail.add(a);
            }
            if (avail.isEmpty()) {
                l.noCounters = true;
                avail.add(0.0);
            }
            int n = l.waiting.size();
            l.startMin = new double[n + 1];
            for (int k = 0; k <= n; k++) {
                int idx = 0;
                for (int j = 1; j < avail.size(); j++) {
                    if (avail.get(j) < avail.get(idx)) {
                        idx = j;
                    }
                }
                double start = avail.get(idx);
                l.startMin[k] = start;
                avail.set(idx, start + l.avgMinutes);
            }
            l.nextJoinerMin = l.startMin[n];

            l.crowdCount = n + l.calledCount;
            l.crowdLevel = crowdLevel(l.crowdCount);
        }
        return out;
    }

    public static ServiceLive of(long serviceId) {
        return all().get(serviceId);
    }

    // ------------------------------------------------------------------
    // API representations
    // ------------------------------------------------------------------

    /** Compact public view of one service queue. */
    public static Row summary(ServiceLive l) {
        Row s = l.service;
        Row r = new Row();
        r.put("id", l.id);
        r.put("name", s.str("name"));
        r.put("prefix", s.str("prefix"));
        r.put("description", s.str("description"));
        r.put("icon", s.str("icon"));
        r.put("color", s.str("color"));
        r.put("isActive", s.bool("isActive"));
        r.put("avgServiceMinutes", Math.round(l.avgMinutes * 10) / 10.0);
        r.put("defaultServiceMinutes", s.dbl("avgServiceMinutes"));
        r.put("waiting", l.waiting.size());
        r.put("called", l.calledCount);
        r.put("serving", l.servingCount);
        r.put("onHold", l.onHold.size());
        r.put("countersTotal", l.counters.size());
        r.put("countersOpen", l.openCounters);
        r.put("countersBusy", l.busyCounters);
        r.put("countersBreak", l.breakCounters);
        r.put("noCounters", l.noCounters);
        r.put("etaMinutes", etaMinutes(l.nextJoinerMin));
        r.put("crowdCount", l.crowdCount);
        r.put("crowdLevel", l.crowdLevel);
        r.put("sampleSize", l.sampleSize);
        List<Row> serving = new ArrayList<>();
        for (Row c : l.counters) {
            if (c.str("currentCode") != null) {
                serving.add(new Row()
                        .with("counterId", c.lng("id"))
                        .with("counterName", c.str("name"))
                        .with("tokenCode", c.str("currentCode"))
                        .with("status", c.str("currentStatus")));
            }
        }
        r.put("nowServing", serving);
        return r;
    }

    /** Adds position / ETA to a token row (only meaningful while the token is WAITING). */
    public static Row decorate(Row token, ServiceLive l) {
        Row r = token.copy();
        r.put("position", null);
        r.put("ahead", null);
        r.put("etaMinutes", null);
        r.put("etaAt", null);
        if (l != null && "WAITING".equals(token.str("status"))) {
            Integer idx = l.indexById.get(token.lng("id"));
            if (idx != null) {
                int eta = etaMinutes(l.startMin[idx]);
                r.put("position", idx + 1);
                r.put("ahead", idx);
                r.put("etaMinutes", eta);
                r.put("etaAt", Times.now().plusMinutes(eta));
            }
        }
        return r;
    }

    /** Overall crowd across all services. */
    public static Row overallCrowd(Map<Long, ServiceLive> live) {
        int waiting = 0;
        int called = 0;
        int serving = 0;
        int onHold = 0;
        int open = 0;
        int total = 0;
        ServiceLive busiest = null;
        for (ServiceLive l : live.values()) {
            waiting += l.waiting.size();
            called += l.calledCount;
            serving += l.servingCount;
            onHold += l.onHold.size();
            open += l.openCounters;
            total += l.counters.size();
            if (busiest == null || l.crowdCount > busiest.crowdCount) {
                busiest = l;
            }
        }
        int count = waiting + called;
        Row r = new Row();
        r.put("waiting", waiting);
        r.put("called", called);
        r.put("serving", serving);
        r.put("onHold", onHold);
        r.put("queueLength", count);
        r.put("countersOpen", open);
        r.put("countersTotal", total);
        r.put("crowdLevel", crowdLevel(count));
        r.put("busiestService", busiest != null && busiest.crowdCount > 0 ? busiest.service.str("name") : null);
        r.put("busiestServiceCount", busiest == null ? 0 : busiest.crowdCount);
        return r;
    }
}
