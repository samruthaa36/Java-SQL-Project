package com.smartqueue.api;

import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.http.Router;
import com.smartqueue.http.Router.Access;
import com.smartqueue.service.QueueService;
import com.smartqueue.service.StaffService;
import com.smartqueue.util.Times;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Staff console endpoints: counter control and token processing. */
public final class StaffApi {

    private StaffApi() {
    }

    static LocalDate dateParam(String raw, LocalDate def) {
        if (raw == null) {
            return def;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Dates must look like 2026-10-06.");
        }
    }

    public static void register(Router r) {
        r.get("/api/staff/overview", Access.STAFF, c -> StaffService.overview(c.user));

        // ---- counter
        r.post("/api/staff/counter/open", Access.STAFF, c -> {
            Row counter = QueueService.openCounter(c.user, c.reqLong("counterId", "Counter"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("counter", counter);
            return out;
        });
        r.post("/api/staff/counter/close", Access.STAFF, c -> {
            QueueService.closeCounter(c.user);
            return null;
        });
        r.post("/api/staff/counter/break", Access.STAFF, c -> {
            Boolean onBreak = c.bool("onBreak");
            Row counter = QueueService.setBreak(c.user, onBreak == null || onBreak);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("counter", counter);
            return out;
        });

        // ---- calling and serving (need an open counter)
        r.post("/api/staff/call-next", Access.STAFF, c -> QueueService.callNext(c.user));
        r.post("/api/staff/tokens/{id}/call", Access.STAFF, c -> QueueService.callToken(c.user, c.pathId("id")));
        r.post("/api/staff/tokens/{id}/start", Access.STAFF, c -> QueueService.start(c.user, c.pathId("id")));
        r.post("/api/staff/tokens/{id}/complete", Access.STAFF, c -> QueueService.complete(c.user, c.pathId("id"), c.str("notes")));
        r.post("/api/staff/tokens/{id}/skip", Access.STAFF, c -> QueueService.skip(c.user, c.pathId("id")));
        r.post("/api/staff/tokens/{id}/recall", Access.STAFF, c -> QueueService.recall(c.user, c.pathId("id")));
        r.post("/api/staff/tokens/{id}/hold", Access.STAFF, c -> QueueService.hold(c.user, c.pathId("id")));

        // ---- queue management (staff of that queue, or administrators)
        r.post("/api/staff/tokens/{id}/resume", Access.STAFF_ADMIN, c -> QueueService.resume(c.user, c.pathId("id")));
        r.post("/api/staff/tokens/{id}/requeue", Access.STAFF_ADMIN, c -> QueueService.requeue(c.user, c.pathId("id")));
        r.post("/api/staff/tokens/{id}/priority", Access.STAFF_ADMIN, c -> {
            Integer p = c.integer("priority");
            if (p == null) {
                throw ApiException.badRequest("Priority is required.");
            }
            return QueueService.setPriority(c.user, c.pathId("id"), p, c.str("reason"));
        });
        r.post("/api/staff/tokens/{id}/move", Access.STAFF_ADMIN, c -> QueueService.move(c.user, c.pathId("id"), c.str("position")));
        r.post("/api/staff/tokens/{id}/cancel", Access.STAFF_ADMIN, c -> QueueService.cancel(c.user, c.pathId("id"), c.str("reason")));

        r.post("/api/staff/walk-in", Access.STAFF_ADMIN, c -> {
            Integer p = c.integer("priority");
            return QueueService.issueWalkIn(c.user, c.reqLong("serviceId", "Service"), c.str("customerName"), c.str("phone"),
                    p == null ? 0 : p, c.str("reason"), c.str("notes"));
        });

        r.get("/api/staff/history", Access.STAFF, c ->
                StaffService.history(c.user, dateParam(c.q("date"), Times.today())));
    }
}
