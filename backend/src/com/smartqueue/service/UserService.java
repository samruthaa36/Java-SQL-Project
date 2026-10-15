package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.service.LiveQueue.ServiceLive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Data for the customer portal. */
public final class UserService {

    private UserService() {
    }

    public static Row dashboard(Row user) {
        long userId = user.lng("id");
        Map<Long, ServiceLive> all = LiveQueue.all();

        Row active = Database.queryFirst(QueueService.tokenSelect() + "WHERE t.user_id = ? AND t.status IN " + QueueService.ACTIVE_IN
                + " ORDER BY t.id DESC", userId);

        Row out = new Row();
        List<Row> services = new ArrayList<>();
        for (ServiceLive l : all.values()) {
            if (l.service.bool("isActive")) {
                services.add(LiveQueue.summary(l));
            }
        }
        out.put("services", services);
        out.put("crowd", LiveQueue.overallCrowd(all));

        if (active == null) {
            out.put("activeToken", null);
        } else {
            ServiceLive live = all.get(active.lng("serviceId"));
            Row v = QueueService.enrich(LiveQueue.decorate(active, live));
            if (live != null) {
                v.put("service", LiveQueue.summary(live));
            }
            out.put("activeToken", v);
        }

        Row stats = Database.queryOne("SELECT COUNT(*) AS visits FROM tokens WHERE user_id = ? AND status = 'COMPLETED'", userId);
        out.put("totalVisits", stats == null ? 0 : stats.lng("visits"));
        out.put("unreadNotifications", NotificationService.unread(userId));
        return out;
    }

    public static Row history(long userId, String status, int page, int size) {
        String filter = null;
        if (status != null) {
            String s = status.toUpperCase();
            if (s.equals("COMPLETED") || s.equals("CANCELLED") || s.equals("SKIPPED")) {
                filter = s;
            }
        }
        List<Row> rows;
        long total;
        int offset = Math.max(0, (page - 1) * size);
        if (filter == null) {
            rows = Database.queryPage(QueueService.tokenSelect() + "WHERE t.user_id = ? ORDER BY t.id DESC", offset, size, userId);
            total = Database.scalarLong("SELECT COUNT(*) FROM tokens WHERE user_id = ?", userId);
        } else {
            rows = Database.queryPage(QueueService.tokenSelect() + "WHERE t.user_id = ? AND t.status = ? ORDER BY t.id DESC",
                    offset, size, userId, filter);
            total = Database.scalarLong("SELECT COUNT(*) FROM tokens WHERE user_id = ? AND status = ?", userId, filter);
        }
        List<Row> items = new ArrayList<>();
        for (Row r : rows) {
            items.add(QueueService.enrich(LiveQueue.decorate(r, null)));
        }
        Row out = new Row();
        out.put("items", items);
        out.put("total", total);
        out.put("page", page);
        out.put("pageSize", size);
        out.put("pages", Math.max(1, (total + size - 1) / size));
        return out;
    }

    public static Row feedback(Row user, long tokenId, Integer rating, String comment) {
        int r = Validate.rangeInt(rating, "Rating", 1, 5);
        String text = Validate.optional(comment, "Comment", 300);
        Row t = Database.queryOne("SELECT id, user_id, status FROM tokens WHERE id = ?", tokenId);
        if (t == null || t.lngOrNull("userId") == null || t.lng("userId") != user.lng("id")) {
            throw ApiException.notFound("Token not found.");
        }
        if (!"COMPLETED".equals(t.str("status"))) {
            throw ApiException.conflict("You can rate a visit after it has been completed.");
        }
        Database.update("UPDATE tokens SET rating = ?, feedback = ? WHERE id = ?", r, text, tokenId);
        return QueueService.view(QueueService.findToken(tokenId));
    }
}
