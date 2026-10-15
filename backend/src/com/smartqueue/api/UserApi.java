package com.smartqueue.api;

import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.http.Router;
import com.smartqueue.http.Router.Access;
import com.smartqueue.service.QueueService;
import com.smartqueue.service.UserService;

/** Customer portal endpoints (role USER). */
public final class UserApi {

    private UserApi() {
    }

    public static void register(Router r) {
        r.get("/api/user/dashboard", Access.USER, c -> UserService.dashboard(c.user));

        r.post("/api/user/tokens", Access.USER, c ->
                QueueService.issueOnline(c.user, c.reqLong("serviceId", "Service"), c.str("notes")));

        r.get("/api/user/tokens/{id}", Access.USER, c -> {
            Row t = QueueService.requireToken(c.pathId("id"));
            if (t.lngOrNull("userId") == null || t.lng("userId") != c.userId()) {
                throw ApiException.notFound("Token not found.");
            }
            return QueueService.view(t);
        });

        r.post("/api/user/tokens/{id}/cancel", Access.USER, c ->
                QueueService.cancel(c.user, c.pathId("id"), null));

        r.get("/api/user/history", Access.USER, c ->
                UserService.history(c.userId(), c.q("status"), c.qInt("page", 1, 1, 10000), c.qInt("size", 10, 1, 50)));

        r.post("/api/user/tokens/{id}/feedback", Access.USER, c ->
                UserService.feedback(c.user, c.pathId("id"), c.integer("rating"), c.str("comment")));
    }
}
