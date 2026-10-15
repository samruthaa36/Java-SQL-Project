package com.smartqueue.api;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.http.Ctx;
import com.smartqueue.http.Router;
import com.smartqueue.http.Router.Access;
import com.smartqueue.service.ActivityLog;
import com.smartqueue.service.AdminService;
import com.smartqueue.service.AnalyticsService;
import com.smartqueue.service.LiveQueue;
import com.smartqueue.service.OptimizationService;
import com.smartqueue.service.QueueService;
import com.smartqueue.service.Settings;
import com.smartqueue.util.Times;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Administrator console endpoints. */
public final class AdminApi {

    private AdminApi() {
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private static boolean flag(Ctx c, String key, boolean def) {
        Boolean b = c.bool(key);
        return b == null ? def : b;
    }

    public static void register(Router r) {
        // ---------------- dashboard, queues, analytics
        r.get("/api/admin/dashboard", Access.ADMIN, c -> AdminService.dashboard());
        r.get("/api/admin/queues", Access.ADMIN, c -> AdminService.liveQueues());
        r.get("/api/admin/analytics", Access.ADMIN, c -> AnalyticsService.analytics(c.qInt("days", 14, 7, 90)));

        r.get("/api/admin/reports", Access.ADMIN, c -> {
            LocalDate to = StaffApi.dateParam(c.q("to"), Times.today());
            LocalDate from = StaffApi.dateParam(c.q("from"), to.minusDays(6));
            return AnalyticsService.report(from, to, c.qLong("serviceId"), c.q("status"));
        });
        r.get("/api/admin/reports/export", Access.ADMIN, c -> {
            LocalDate to = StaffApi.dateParam(c.q("to"), Times.today());
            LocalDate from = StaffApi.dateParam(c.q("from"), to.minusDays(6));
            ActivityLog.log(c.user, "REPORT_EXPORTED", "report", null, "CSV " + from + " to " + to);
            return AnalyticsService.exportCsv(from, to, c.qLong("serviceId"), c.q("status"));
        });

        // ---------------- recommendations
        r.get("/api/admin/recommendations", Access.ADMIN, c -> map("items", OptimizationService.recommend(LiveQueue.all())));
        r.post("/api/admin/recommendations/apply", Access.ADMIN, c -> {
            OptimizationService.apply(c.user, c.str("type"), c.lng("counterId"), c.lng("targetServiceId"), c.lng("staffId"));
            return map("items", OptimizationService.recommend(LiveQueue.all()));
        });

        // ---------------- users
        r.get("/api/admin/users", Access.ADMIN, c ->
                AdminService.listUsers(c.q("search"), c.q("role"), c.qInt("page", 1, 1, 10000), c.qInt("size", 10, 1, 100)));
        r.post("/api/admin/users", Access.ADMIN, c -> {
            Object pw = c.body().get("password");
            return AdminService.createUser(c.user, c.str("fullName"), c.str("email"), c.str("phone"),
                    pw == null ? null : pw.toString(), c.str("role"), flag(c, "isPriority", false), c.str("priorityReason"));
        });
        r.put("/api/admin/users/{id}", Access.ADMIN, c ->
                AdminService.updateUser(c.user, c.pathId("id"), c.str("fullName"), c.str("email"),
                        c.has("phone") ? (c.str("phone") == null ? "" : c.str("phone")) : null,
                        c.str("role"), c.bool("isActive"), c.bool("isPriority"),
                        c.has("priorityReason") ? (c.str("priorityReason") == null ? "" : c.str("priorityReason")) : null));
        r.post("/api/admin/users/{id}/reset-password", Access.ADMIN, c ->
                map("temporaryPassword", AdminService.resetPassword(c.user, c.pathId("id"))));
        r.delete("/api/admin/users/{id}", Access.ADMIN, c -> {
            AdminService.deleteUser(c.user, c.pathId("id"));
            return null;
        });
        r.get("/api/admin/staff-available", Access.ADMIN, c -> map("items", Database.query(
                "SELECT u.id, u.full_name FROM users u WHERE u.role = 'STAFF' AND u.is_active = 1 AND NOT EXISTS "
                        + "(SELECT 1 FROM counters c WHERE c.staff_id = u.id AND c.is_deleted = 0 AND c.status IN ('OPEN','BREAK')) "
                        + "ORDER BY LOWER(u.full_name)")));

        // ---------------- services
        r.get("/api/admin/services", Access.ADMIN, c -> map("items", AdminService.listServices()));
        r.post("/api/admin/services", Access.ADMIN, c ->
                AdminService.createService(c.user, c.str("name"), c.str("prefix"), c.str("description"), c.str("icon"),
                        c.str("color"), c.dbl("avgServiceMinutes"), c.bool("isActive")));
        r.put("/api/admin/services/{id}", Access.ADMIN, c ->
                AdminService.updateService(c.user, c.pathId("id"), c.str("name"), c.str("prefix"),
                        c.has("description") ? (c.str("description") == null ? "" : c.str("description")) : null,
                        c.str("icon"), c.str("color"), c.dbl("avgServiceMinutes"), c.bool("isActive")));
        r.delete("/api/admin/services/{id}", Access.ADMIN, c -> {
            AdminService.deleteService(c.user, c.pathId("id"));
            return null;
        });

        // ---------------- counters
        r.get("/api/admin/counters", Access.ADMIN, c -> map("items", AdminService.listCounters()));
        r.post("/api/admin/counters", Access.ADMIN, c ->
                AdminService.createCounter(c.user, c.str("name"), c.reqLong("serviceId", "Service")));
        r.put("/api/admin/counters/{id}", Access.ADMIN, c ->
                AdminService.updateCounter(c.user, c.pathId("id"), c.str("name"), c.lng("serviceId")));
        r.delete("/api/admin/counters/{id}", Access.ADMIN, c -> {
            AdminService.deleteCounter(c.user, c.pathId("id"));
            return null;
        });
        r.post("/api/admin/counters/{id}/open", Access.ADMIN, c -> {
            QueueService.adminOpenCounter(c.user, c.pathId("id"), c.reqLong("staffId", "Staff member"));
            return null;
        });
        r.post("/api/admin/counters/{id}/close", Access.ADMIN, c -> {
            QueueService.adminCloseCounter(c.user, c.pathId("id"));
            return null;
        });
        r.post("/api/admin/counters/{id}/reassign", Access.ADMIN, c -> {
            OptimizationService.reassign(c.user, c.pathId("id"), c.reqLong("serviceId", "Service"));
            return null;
        });

        // ---------------- settings & priority rules
        r.get("/api/admin/settings", Access.ADMIN, c -> map("items", Settings.describe()));
        r.put("/api/admin/settings", Access.ADMIN, c -> {
            if (c.body().isEmpty()) {
                throw ApiException.badRequest("Nothing to update.");
            }
            Settings.update(c.body(), c.user);
            return map("items", Settings.describe());
        });

        // ---------------- activity log
        r.get("/api/admin/activity", Access.ADMIN, c -> {
            int size = c.qInt("size", 20, 1, 100);
            int page = c.qInt("page", 1, 1, 10000);
            String search = c.q("search");
            List<Row> items = ActivityLog.recent(size, (page - 1) * size, search);
            long total = ActivityLog.count(search);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", items);
            out.put("total", total);
            out.put("page", page);
            out.put("pages", Math.max(1, (total + size - 1) / size));
            return out;
        });

        // ---------------- demo tools
        r.post("/api/admin/demo/simulate-crowd", Access.ADMIN, c -> {
            Integer n = c.integer("count");
            return AdminService.simulateCrowd(c.user, n == null ? 10 : n);
        });
        r.post("/api/admin/demo/clear-waiting", Access.ADMIN, c -> AdminService.clearWaiting(c.user));
    }
}
