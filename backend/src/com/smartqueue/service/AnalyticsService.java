package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.http.Raw;
import com.smartqueue.util.Times;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Analytics, historical analysis and reports.
 * Aggregation is done in Java on plain rows so the numbers are identical on every database engine.
 */
public final class AnalyticsService {

    private static final String[] WEEKDAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    private AnalyticsService() {
    }

    public static String hourLabel(int h) {
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + (h < 12 ? " AM" : " PM");
    }

    public static String windowLabel(int startHour, int hours) {
        return hourLabel(startHour % 24) + "–" + hourLabel((startHour + hours) % 24);
    }

    private static double avg(double sum, int n) {
        return n == 0 ? 0 : Math.round(sum / n * 10.0) / 10.0;
    }

    private static final class Acc {
        int total;
        int completed;
        int cancelled;
        int skipped;
        double waitSum;
        int waitN;
        double svcSum;
        int svcN;
        double ratingSum;
        int ratingN;
    }

    private static void add(Acc a, Row t) {
        a.total++;
        String st = t.str("status");
        if ("COMPLETED".equals(st)) {
            a.completed++;
            if (t.integer("serviceSeconds") > 0) {
                a.svcSum += t.dbl("serviceSeconds") / 60.0;
                a.svcN++;
            }
            if (!t.isNull("rating")) {
                a.ratingSum += t.dbl("rating");
                a.ratingN++;
            }
        } else if ("CANCELLED".equals(st)) {
            a.cancelled++;
        } else if ("SKIPPED".equals(st)) {
            a.skipped++;
        }
        LocalDateTime created = t.time("createdAt");
        LocalDateTime called = t.time("calledAt");
        if (created != null && called != null) {
            a.waitSum += Math.max(0, Times.minutes(created, called));
            a.waitN++;
        }
    }

    // ------------------------------------------------------------------
    // Main analytics
    // ------------------------------------------------------------------
    public static Row analytics(int days) {
        LocalDate today = Times.today();
        LocalDate fromDate = today.minusDays(days - 1);
        LocalDateTime from = fromDate.atStartOfDay();
        List<Row> tokens = Database.query("SELECT id, service_id, status, priority, created_at, called_at, ended_at, service_seconds, "
                + "counter_id, served_by, rating, estimated_wait_min, source FROM tokens WHERE created_at >= ?", from);
        List<Row> services = Database.query("SELECT id, name, prefix, color FROM services ORDER BY id");
        Map<Long, Row> counterRows = new HashMap<>();
        for (Row c : Database.query("SELECT id, name FROM counters")) {
            counterRows.put(c.lng("id"), c);
        }
        Map<Long, Row> staffRows = new HashMap<>();
        for (Row u : Database.query("SELECT id, full_name FROM users WHERE role IN ('STAFF','ADMIN')")) {
            staffRows.put(u.lng("id"), u);
        }

        Map<LocalDate, Acc> daily = new TreeMap<>();
        for (int i = 0; i < days; i++) {
            daily.put(fromDate.plusDays(i), new Acc());
        }
        Map<Long, Acc> byService = new LinkedHashMap<>();
        for (Row s : services) {
            byService.put(s.lng("id"), new Acc());
        }
        Acc totals = new Acc();
        int[] hourly = new int[24];
        int[][] heat = new int[7][24];
        Map<Long, Acc> byCounter = new HashMap<>();
        Map<Long, Acc> byStaff = new HashMap<>();
        int active = 0;
        double errSum = 0;
        int errN = 0;
        int within5 = 0;
        Set<LocalDate> operatingDays = new HashSet<>();
        Map<Integer, Set<LocalDate>> operatingByDow = new HashMap<>();

        for (Row t : tokens) {
            LocalDateTime created = t.time("createdAt");
            if (created == null) {
                continue;
            }
            LocalDate d = created.toLocalDate();
            Acc da = daily.get(d);
            if (da == null) {
                continue;
            }
            add(da, t);
            add(totals, t);
            Acc sa = byService.get(t.lng("serviceId"));
            if (sa != null) {
                add(sa, t);
            }
            hourly[created.getHour()]++;
            int dow = created.getDayOfWeek().getValue() - 1;
            heat[dow][created.getHour()]++;
            operatingDays.add(d);
            operatingByDow.computeIfAbsent(dow, k -> new HashSet<>()).add(d);

            String st = t.str("status");
            if ("WAITING".equals(st) || "CALLED".equals(st) || "SERVING".equals(st) || "ON_HOLD".equals(st)) {
                active++;
            }
            if ("COMPLETED".equals(st)) {
                if (t.lngOrNull("counterId") != null) {
                    add(byCounter.computeIfAbsent(t.lng("counterId"), k -> new Acc()), t);
                }
                if (t.lngOrNull("servedBy") != null) {
                    add(byStaff.computeIfAbsent(t.lng("servedBy"), k -> new Acc()), t);
                }
            }
            LocalDateTime called = t.time("calledAt");
            if (called != null && !t.isNull("estimatedWaitMin") && ("COMPLETED".equals(st) || "SKIPPED".equals(st))) {
                double actual = Math.max(0, Times.minutes(created, called));
                double err = Math.abs(actual - t.dbl("estimatedWaitMin"));
                errSum += err;
                errN++;
                if (err <= 5.0) {
                    within5++;
                }
            }
        }
        int opDays = Math.max(1, operatingDays.size());

        // --- daily series
        List<Row> dailyList = new ArrayList<>();
        for (Map.Entry<LocalDate, Acc> e : daily.entrySet()) {
            Acc a = e.getValue();
            dailyList.add(new Row()
                    .with("date", e.getKey().toString())
                    .with("label", e.getKey().getDayOfMonth() + " " + e.getKey().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                    .with("weekday", e.getKey().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                    .with("total", a.total)
                    .with("completed", a.completed)
                    .with("cancelled", a.cancelled)
                    .with("skipped", a.skipped)
                    .with("avgWaitMinutes", avg(a.waitSum, a.waitN))
                    .with("avgServiceMinutes", avg(a.svcSum, a.svcN)));
        }

        // --- per service
        List<Row> demand = new ArrayList<>();
        List<Row> serviceTime = new ArrayList<>();
        String topService = null;
        int topServiceCount = -1;
        for (Row s : services) {
            Acc a = byService.get(s.lng("id"));
            demand.add(new Row()
                    .with("id", s.lng("id"))
                    .with("name", s.str("name"))
                    .with("prefix", s.str("prefix"))
                    .with("color", s.str("color"))
                    .with("count", a.total)
                    .with("share", totals.total == 0 ? 0 : Math.round(a.total * 1000.0 / totals.total) / 10.0));
            serviceTime.add(new Row()
                    .with("id", s.lng("id"))
                    .with("name", s.str("name"))
                    .with("color", s.str("color"))
                    .with("avgServiceMinutes", avg(a.svcSum, a.svcN))
                    .with("avgWaitMinutes", avg(a.waitSum, a.waitN))
                    .with("avgRating", a.ratingN == 0 ? null : (Object) avg(a.ratingSum, a.ratingN)));
            if (a.total > topServiceCount) {
                topServiceCount = a.total;
                topService = s.str("name");
            }
        }

        // --- peak hours (average tokens per operating day)
        List<Row> peakHours = new ArrayList<>();
        int bestHour = 0;
        for (int h = 0; h < 24; h++) {
            peakHours.add(new Row()
                    .with("hour", h)
                    .with("label", hourLabel(h))
                    .with("total", hourly[h])
                    .with("avgPerDay", Math.round(hourly[h] * 10.0 / opDays) / 10.0));
            if (hourly[h] > hourly[bestHour]) {
                bestHour = h;
            }
        }

        // --- heatmap & busiest weekdays (average per operating day of that weekday)
        List<Row> heatmap = new ArrayList<>();
        List<Row> busiestDays = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            int occ = operatingByDow.containsKey(d) ? operatingByDow.get(d).size() : 0;
            List<Object> cells = new ArrayList<>();
            int dayTotal = 0;
            for (int h = 0; h < 24; h++) {
                cells.add(occ == 0 ? 0 : Math.round(heat[d][h] * 10.0 / occ) / 10.0);
                dayTotal += heat[d][h];
            }
            heatmap.add(new Row().with("weekday", WEEKDAYS[d].substring(0, 3)).with("cells", cells));
            busiestDays.add(new Row()
                    .with("weekday", WEEKDAYS[d])
                    .with("short", WEEKDAYS[d].substring(0, 3))
                    .with("avgTokens", occ == 0 ? 0 : Math.round(dayTotal * 10.0 / occ) / 10.0)
                    .with("days", occ));
        }
        String busiestWeekday = null;
        double busiestVal = -1;
        for (Row r : busiestDays) {
            if (r.dbl("avgTokens") > busiestVal) {
                busiestVal = r.dbl("avgTokens");
                busiestWeekday = r.str("weekday");
            }
        }

        // --- counters & staff
        List<Row> counterPerf = new ArrayList<>();
        for (Map.Entry<Long, Acc> e : byCounter.entrySet()) {
            Acc a = e.getValue();
            Row c = counterRows.get(e.getKey());
            counterPerf.add(new Row()
                    .with("id", e.getKey())
                    .with("name", c == null ? "Counter #" + e.getKey() : c.str("name"))
                    .with("served", a.completed)
                    .with("avgServiceMinutes", avg(a.svcSum, a.svcN))
                    .with("avgRating", a.ratingN == 0 ? null : (Object) avg(a.ratingSum, a.ratingN))
                    .with("ratings", a.ratingN));
        }
        counterPerf.sort(Comparator.comparingInt((Row r) -> -r.integer("served")));
        List<Row> staffPerf = new ArrayList<>();
        for (Map.Entry<Long, Acc> e : byStaff.entrySet()) {
            Acc a = e.getValue();
            Row u = staffRows.get(e.getKey());
            staffPerf.add(new Row()
                    .with("id", e.getKey())
                    .with("name", u == null ? "Staff #" + e.getKey() : u.str("fullName"))
                    .with("served", a.completed)
                    .with("avgServiceMinutes", avg(a.svcSum, a.svcN))
                    .with("avgRating", a.ratingN == 0 ? null : (Object) avg(a.ratingSum, a.ratingN))
                    .with("ratings", a.ratingN));
        }
        staffPerf.sort(Comparator.comparingInt((Row r) -> -r.integer("served")));

        // --- summary
        Row summary = new Row();
        summary.put("days", days);
        summary.put("total", totals.total);
        summary.put("completed", totals.completed);
        summary.put("cancelled", totals.cancelled);
        summary.put("skipped", totals.skipped);
        summary.put("active", active);
        summary.put("completionRate", totals.total == 0 ? 0 : Math.round(totals.completed * 1000.0 / totals.total) / 10.0);
        summary.put("cancelRate", totals.total == 0 ? 0 : Math.round(totals.cancelled * 1000.0 / totals.total) / 10.0);
        summary.put("noShowRate", totals.total == 0 ? 0 : Math.round(totals.skipped * 1000.0 / totals.total) / 10.0);
        summary.put("avgWaitMinutes", avg(totals.waitSum, totals.waitN));
        summary.put("avgServiceMinutes", avg(totals.svcSum, totals.svcN));
        summary.put("avgRating", totals.ratingN == 0 ? null : (Object) avg(totals.ratingSum, totals.ratingN));
        summary.put("ratings", totals.ratingN);
        summary.put("avgPerDay", Math.round(totals.total * 10.0 / opDays) / 10.0);
        summary.put("busiestWeekday", busiestWeekday);
        summary.put("peakHour", bestHour);
        summary.put("peakHourLabel", windowLabel(bestHour, 1));
        summary.put("mostRequestedService", topService);

        Row accuracy = new Row();
        accuracy.put("samples", errN);
        accuracy.put("meanAbsErrorMinutes", avg(errSum, errN));
        accuracy.put("within5MinPercent", errN == 0 ? 0 : Math.round(within5 * 1000.0 / errN) / 10.0);

        Row out = new Row();
        out.put("from", fromDate);
        out.put("to", today);
        out.put("summary", summary);
        out.put("daily", dailyList);
        out.put("serviceDemand", demand);
        out.put("serviceTime", serviceTime);
        out.put("peakHours", peakHours);
        out.put("heatmap", heatmap);
        out.put("busiestDays", busiestDays);
        out.put("counterPerformance", counterPerf);
        out.put("staffPerformance", staffPerf);
        out.put("predictionAccuracy", accuracy);
        out.put("peakCrowdPeriods", peakCrowd(tokens));
        return out;
    }

    // ------------------------------------------------------------------
    // Peak crowd periods: maximum number of people waiting at the same time
    // ------------------------------------------------------------------
    private static List<Row> peakCrowd(List<Row> tokens) {
        LocalDateTime now = Times.now();
        // events: +1 when a person joins the queue, -1 when they leave it (called / cancelled / skipped)
        List<Object[]> events = new ArrayList<>();
        for (Row t : tokens) {
            LocalDateTime start = t.time("createdAt");
            if (start == null) {
                continue;
            }
            LocalDateTime end = t.time("calledAt");
            if (end == null) {
                end = t.time("endedAt");
            }
            if (end == null) {
                end = now;
            }
            if (end.isBefore(start)) {
                end = start;
            }
            events.add(new Object[]{start, 1});
            events.add(new Object[]{end, -1});
        }
        events.sort((a, b) -> {
            int c = ((LocalDateTime) a[0]).compareTo((LocalDateTime) b[0]);
            return c != 0 ? c : Integer.compare((Integer) a[1], (Integer) b[1]);
        });
        Map<LocalDateTime, Integer> bucketMax = new HashMap<>();
        int current = 0;
        LocalDateTime prev = null;
        for (Object[] e : events) {
            LocalDateTime t = (LocalDateTime) e[0];
            if (prev != null && current > 0) {
                // concurrency "current" held from prev until t: mark every hour bucket it touches
                LocalDateTime cursor = prev.withMinute(0).withSecond(0).withNano(0);
                int guard = 0;
                while (cursor.isBefore(t) && guard++ < 48) {
                    bucketMax.merge(cursor, current, Math::max);
                    cursor = cursor.plusHours(1);
                }
            }
            current += (Integer) e[1];
            prev = t;
        }
        List<Map.Entry<LocalDateTime, Integer>> sorted = new ArrayList<>(bucketMax.entrySet());
        sorted.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : b.getKey().compareTo(a.getKey());
        });
        List<Row> out = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
        for (Map.Entry<LocalDateTime, Integer> e : sorted) {
            if (out.size() >= 6) {
                break;
            }
            if (e.getValue() <= 0) {
                continue;
            }
            LocalDateTime k = e.getKey();
            out.add(new Row()
                    .with("date", k.toLocalDate().toString())
                    .with("label", k.format(fmt) + ", " + windowLabel(k.getHour(), 1))
                    .with("peakWaiting", e.getValue()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Reports
    // ------------------------------------------------------------------
    private static final int REPORT_LIMIT = 5000;

    private static List<Row> reportRows(LocalDate from, LocalDate to, Long serviceId, String status) {
        if (from == null || to == null) {
            throw ApiException.badRequest("Please choose a start and an end date.");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date must not be before the start date.");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw ApiException.badRequest("Please choose a period of at most one year.");
        }
        StringBuilder sql = new StringBuilder(QueueService.tokenSelect());
        sql.append("WHERE t.created_at >= ? AND t.created_at < ?");
        List<Object> params = new ArrayList<>();
        params.add(from.atStartOfDay());
        params.add(to.plusDays(1).atStartOfDay());
        if (serviceId != null) {
            sql.append(" AND t.service_id = ?");
            params.add(serviceId);
        }
        if (status != null) {
            String s = status.toUpperCase();
            if (!s.matches("WAITING|CALLED|SERVING|ON_HOLD|COMPLETED|SKIPPED|CANCELLED")) {
                throw ApiException.badRequest("Unknown status filter.");
            }
            sql.append(" AND t.status = ?");
            params.add(s);
        }
        sql.append(" ORDER BY t.created_at DESC, t.id DESC");
        List<Row> rows = Database.queryTop(sql.toString(), REPORT_LIMIT + 1, params.toArray());
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(QueueService.enrich(r));
        }
        return out;
    }

    public static Row report(LocalDate from, LocalDate to, Long serviceId, String status) {
        List<Row> rows = reportRows(from, to, serviceId, status);
        boolean truncated = rows.size() > REPORT_LIMIT;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, REPORT_LIMIT));
        }
        Acc a = new Acc();
        for (Row r : rows) {
            add(a, r);
        }
        Row summary = new Row();
        summary.put("total", a.total);
        summary.put("completed", a.completed);
        summary.put("cancelled", a.cancelled);
        summary.put("skipped", a.skipped);
        summary.put("avgWaitMinutes", avg(a.waitSum, a.waitN));
        summary.put("avgServiceMinutes", avg(a.svcSum, a.svcN));
        summary.put("avgRating", a.ratingN == 0 ? null : (Object) avg(a.ratingSum, a.ratingN));
        List<Row> slim = new ArrayList<>();
        for (Row r : rows) {
            slim.add(new Row()
                    .with("id", r.lng("id"))
                    .with("tokenCode", r.str("tokenCode"))
                    .with("tokenDate", r.date("tokenDate"))
                    .with("serviceName", r.str("serviceName"))
                    .with("customerName", r.str("customerName"))
                    .with("source", r.str("source"))
                    .with("priority", r.integer("priority"))
                    .with("status", r.str("status"))
                    .with("counterName", r.str("counterName"))
                    .with("servedByName", r.str("servedByName"))
                    .with("createdAt", r.time("createdAt"))
                    .with("calledAt", r.time("calledAt"))
                    .with("endedAt", r.time("endedAt"))
                    .with("waitedMinutes", r.get("waitedMinutes"))
                    .with("serviceMinutes", r.get("serviceMinutes"))
                    .with("rating", r.get("rating"))
                    .with("closeReason", r.str("closeReason")));
        }
        return new Row().with("summary", summary).with("items", slim).with("truncated", truncated).with("limit", REPORT_LIMIT);
    }

    private static String csv(Object v) {
        if (v == null) {
            return "";
        }
        String s = v.toString();
        // neutralise spreadsheet formula injection
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("^-?[0-9.]+$")) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static Raw exportCsv(LocalDate from, LocalDate to, Long serviceId, String status) {
        List<Row> rows = reportRows(from, to, serviceId, status);
        if (rows.size() > REPORT_LIMIT) {
            rows = rows.subList(0, REPORT_LIMIT);
        }
        DateTimeFormatter dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append("Date,Token,Service,Customer,Source,Priority,Status,Counter,Served by,Joined,Called,Ended,Wait (min),Service (min),Rating,Reason\r\n");
        String[] prio = {"Normal", "Priority", "Emergency"};
        for (Row r : rows) {
            int p = Math.max(0, Math.min(2, r.integer("priority")));
            sb.append(csv(r.date("tokenDate"))).append(',')
                    .append(csv(r.str("tokenCode"))).append(',')
                    .append(csv(r.str("serviceName"))).append(',')
                    .append(csv(r.str("customerName"))).append(',')
                    .append(csv(r.str("source"))).append(',')
                    .append(csv(prio[p])).append(',')
                    .append(csv(r.str("status"))).append(',')
                    .append(csv(r.str("counterName"))).append(',')
                    .append(csv(r.str("servedByName"))).append(',')
                    .append(csv(r.time("createdAt") == null ? null : r.time("createdAt").format(dt))).append(',')
                    .append(csv(r.time("calledAt") == null ? null : r.time("calledAt").format(dt))).append(',')
                    .append(csv(r.time("endedAt") == null ? null : r.time("endedAt").format(dt))).append(',')
                    .append(csv(r.get("waitedMinutes"))).append(',')
                    .append(csv(r.get("serviceMinutes"))).append(',')
                    .append(csv(r.get("rating"))).append(',')
                    .append(csv(r.str("closeReason"))).append("\r\n");
        }
        String name = "smartqueue-report-" + from + "_to_" + to + ".csv";
        return Raw.csv(sb.toString(), name);
    }

    static String dayName(DayOfWeek d) {
        return WEEKDAYS[d.getValue() - 1];
    }
}
