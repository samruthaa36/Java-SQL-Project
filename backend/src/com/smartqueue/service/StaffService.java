package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.service.LiveQueue.ServiceLive;
import com.smartqueue.util.Times;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Data for the staff console. */
public final class StaffService {

    private StaffService() {
    }

    private static Row counterView(Row counter, ServiceLive live) {
        Row c = new Row();
        c.put("id", counter.lng("id"));
        c.put("name", counter.str("name"));
        c.put("status", counter.str("status"));
        c.put("serviceId", live.id);
        c.put("serviceName", live.service.str("name"));
        c.put("serviceColor", live.service.str("color"));
        c.put("servicePrefix", live.service.str("prefix"));
        c.put("openedAt", counter.time("openedAt"));
        return c;
    }

    public static Row overview(Row staff) {
        long staffId = staff.lng("id");
        LocalDateTime now = Times.now();
        Map<Long, ServiceLive> all = LiveQueue.all();
        Row counter = QueueService.counterOfStaff(staffId);

        Row out = new Row();
        out.put("staff", new Row().with("id", staffId).with("fullName", staff.str("fullName")));
        out.put("noShowMinutes", Settings.getInt("no_show_minutes"));
        out.put("autoAssign", Settings.getBool("auto_assign"));

        // closed counters the staff member can open
        List<Row> available = new ArrayList<>();
        for (ServiceLive l : all.values()) {
            for (Row c : l.counters) {
                if ("CLOSED".equals(c.str("status"))) {
                    available.add(new Row()
                            .with("id", c.lng("id"))
                            .with("name", c.str("name"))
                            .with("serviceId", l.id)
                            .with("serviceName", l.service.str("name"))
                            .with("serviceColor", l.service.str("color"))
                            .with("waiting", l.waiting.size())
                            .with("countersOpen", l.openCounters));
                }
            }
        }
        out.put("availableCounters", available);
        out.put("services", servicesLite(all));

        if (counter == null) {
            out.put("counter", null);
            out.put("current", null);
            out.put("queue", new ArrayList<>());
            out.put("onHold", new ArrayList<>());
            out.put("skipped", new ArrayList<>());
            out.put("serviceInfo", null);
            out.put("today", todayStats(staffId));
            out.put("recent", recent(staffId, 6));
            return out;
        }

        ServiceLive live = all.get(counter.lng("serviceId"));
        out.put("counter", counterView(counter, live));
        out.put("serviceInfo", LiveQueue.summary(live));

        // current customer
        Row cur = Database.queryFirst(QueueService.tokenSelect() + "WHERE t.counter_id = ? AND t.status IN ('CALLED','SERVING') "
                + "ORDER BY t.id DESC", counter.lng("id"));
        if (cur == null) {
            out.put("current", null);
        } else {
            Row v = QueueService.view(cur);
            LocalDateTime lastCalled = cur.time("lastCalledAt");
            if ("CALLED".equals(cur.str("status")) && lastCalled != null) {
                v.put("noShowDeadline", lastCalled.plusMinutes(Settings.getInt("no_show_minutes")));
            }
            out.put("current", v);
        }

        // waiting queue in serving order
        List<Row> queue = new ArrayList<>();
        for (int i = 0; i < live.waiting.size(); i++) {
            Row t = live.waiting.get(i);
            LocalDateTime created = t.time("createdAt");
            queue.add(new Row()
                    .with("id", t.lng("id"))
                    .with("tokenCode", t.str("tokenCode"))
                    .with("customerName", t.str("customerName"))
                    .with("customerPhone", t.str("customerPhone"))
                    .with("priority", t.integer("priority"))
                    .with("priorityReason", t.str("priorityReason"))
                    .with("source", t.str("source"))
                    .with("notes", t.str("notes"))
                    .with("position", i + 1)
                    .with("createdAt", created)
                    .with("waitedMinutes", created == null ? 0 : Math.max(0, Math.round(Times.minutes(created, now))))
                    .with("etaMinutes", LiveQueue.etaMinutes(live.startMin[i])));
        }
        out.put("queue", queue);

        List<Row> hold = new ArrayList<>();
        for (Row t : live.onHold) {
            hold.add(new Row()
                    .with("id", t.lng("id"))
                    .with("tokenCode", t.str("tokenCode"))
                    .with("customerName", t.str("customerName"))
                    .with("priority", t.integer("priority")));
        }
        out.put("onHold", hold);

        out.put("skipped", Database.queryTop("SELECT t.id, t.token_code, t.customer_name, t.ended_at, t.close_reason, t.priority "
                + "FROM tokens t WHERE t.service_id = ? AND t.token_date = ? AND t.status = 'SKIPPED' "
                + "ORDER BY t.ended_at DESC", 10, live.id, Times.today()));
        out.put("today", todayStats(staffId));
        out.put("recent", recent(staffId, 6));
        return out;
    }

    private static List<Row> servicesLite(Map<Long, ServiceLive> all) {
        List<Row> list = new ArrayList<>();
        for (ServiceLive l : all.values()) {
            if (l.service.bool("isActive")) {
                list.add(new Row()
                        .with("id", l.id)
                        .with("name", l.service.str("name"))
                        .with("prefix", l.service.str("prefix"))
                        .with("color", l.service.str("color"))
                        .with("waiting", l.waiting.size()));
            }
        }
        return list;
    }

    public static Row todayStats(long staffId) {
        LocalDateTime start = Times.today().atStartOfDay();
        List<Row> rows = Database.query("SELECT status, service_seconds, rating FROM tokens WHERE served_by = ? AND ended_at >= ? "
                + "AND status IN ('COMPLETED','SKIPPED')", staffId, start);
        int served = 0;
        int skipped = 0;
        double secs = 0;
        double ratingSum = 0;
        int ratings = 0;
        for (Row r : rows) {
            if ("COMPLETED".equals(r.str("status"))) {
                served++;
                secs += r.dbl("serviceSeconds");
                if (!r.isNull("rating")) {
                    ratingSum += r.dbl("rating");
                    ratings++;
                }
            } else {
                skipped++;
            }
        }
        Row s = new Row();
        s.put("served", served);
        s.put("skipped", skipped);
        s.put("avgServiceMinutes", served == 0 ? 0 : Math.round(secs / served / 6.0) / 10.0);
        s.put("avgRating", ratings == 0 ? null : Math.round(ratingSum / ratings * 10.0) / 10.0);
        return s;
    }

    private static List<Row> recent(long staffId, int limit) {
        List<Row> rows = Database.queryTop(QueueService.tokenSelect() + "WHERE t.served_by = ? AND t.status IN ('COMPLETED','SKIPPED') "
                + "AND t.ended_at >= ? ORDER BY t.ended_at DESC", limit, staffId, Times.today().atStartOfDay());
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(QueueService.enrich(r));
        }
        return out;
    }

    /** Service history of the staff member for one day. */
    public static Row history(Row staff, LocalDate date) {
        long staffId = staff.lng("id");
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = from.plusDays(1);
        List<Row> rows = Database.queryTop(QueueService.tokenSelect() + "WHERE t.served_by = ? AND t.ended_at >= ? AND t.ended_at < ? "
                + "AND t.status IN ('COMPLETED','SKIPPED','CANCELLED') ORDER BY t.ended_at DESC", 500, staffId, from, to);
        List<Row> items = new ArrayList<>();
        int completed = 0;
        int skipped = 0;
        double secs = 0;
        double ratingSum = 0;
        int ratings = 0;
        for (Row r : rows) {
            items.add(QueueService.enrich(r));
            if ("COMPLETED".equals(r.str("status"))) {
                completed++;
                secs += r.dbl("serviceSeconds");
                if (!r.isNull("rating")) {
                    ratingSum += r.dbl("rating");
                    ratings++;
                }
            } else if ("SKIPPED".equals(r.str("status"))) {
                skipped++;
            }
        }
        Row summary = new Row();
        summary.put("date", date);
        summary.put("completed", completed);
        summary.put("skipped", skipped);
        summary.put("avgServiceMinutes", completed == 0 ? 0 : Math.round(secs / completed / 6.0) / 10.0);
        summary.put("avgRating", ratings == 0 ? null : Math.round(ratingSum / ratings * 10.0) / 10.0);
        return new Row().with("summary", summary).with("items", items);
    }
}
