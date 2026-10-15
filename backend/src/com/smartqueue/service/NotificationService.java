package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.util.Log;
import com.smartqueue.util.Times;

import java.util.List;

/** In-app notifications (the polling front end shows them as toasts and in the bell menu). */
public final class NotificationService {

    private NotificationService() {
    }

    /** Creates a notification. A failure here must never break the queue operation that caused it. */
    public static void notify(Long userId, Long tokenId, String type, String title, String message) {
        if (userId == null) {
            return;
        }
        try {
            String t = title.length() > 120 ? title.substring(0, 120) : title;
            String m = message.length() > 300 ? message.substring(0, 300) : message;
            Database.update("INSERT INTO notifications (id, user_id, token_id, type, title, message, is_read, created_at) "
                    + "VALUES (notifications_seq.NEXTVAL, ?,?,?,?,?,0,?)", userId, tokenId, type, t, m, Times.now());
        } catch (RuntimeException e) {
            Log.warn("Could not create notification: " + e.getMessage());
        }
    }

    public static List<Row> list(long userId, int limit) {
        return Database.queryTop("SELECT id, token_id, type, title, message, is_read, created_at FROM notifications "
                + "WHERE user_id = ? ORDER BY id DESC", limit, userId);
    }

    public static long unread(long userId) {
        return Database.scalarLong("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0", userId);
    }

    public static void markRead(long userId, long id) {
        int n = Database.update("UPDATE notifications SET is_read = 1 WHERE id = ? AND user_id = ?", id, userId);
        if (n == 0 && Database.scalarLong("SELECT COUNT(*) FROM notifications WHERE id = ? AND user_id = ?", id, userId) == 0) {
            throw ApiException.notFound("Notification not found.");
        }
    }

    public static void markAllRead(long userId) {
        Database.update("UPDATE notifications SET is_read = 1 WHERE user_id = ? AND is_read = 0", userId);
    }

    public static void clearAll(long userId) {
        Database.update("DELETE FROM notifications WHERE user_id = ?", userId);
    }
}
