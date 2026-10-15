package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.util.Log;
import com.smartqueue.util.Times;

import java.util.List;

/** Audit trail shown to administrators. Logging must never break the actual operation. */
public final class ActivityLog {

    private ActivityLog() {
    }

    public static void log(Row actor, String action, String entityType, Long entityId, String details) {
        try {
            String name = actor == null ? "System" : actor.str("fullName");
            Long actorId = actor == null ? null : actor.lngOrNull("id");
            String d = details == null ? null : (details.length() > 500 ? details.substring(0, 500) : details);
            Database.update("INSERT INTO activity_log (id, actor_id, actor_name, action, entity_type, entity_id, details, created_at) "
                            + "VALUES (activity_log_seq.NEXTVAL, ?,?,?,?,?,?,?)",
                    actorId, name == null ? "System" : name, action, entityType, entityId, d, Times.now());
        } catch (RuntimeException e) {
            Log.warn("Could not write activity log: " + e.getMessage());
        }
    }

    public static List<Row> recent(int limit, int offset, String search) {
        if (search == null) {
            return Database.queryPage("SELECT * FROM activity_log ORDER BY id DESC", offset, limit);
        }
        String like = "%" + search.replace("%", "").replace("_", "").toLowerCase() + "%";
        return Database.queryPage("SELECT * FROM activity_log WHERE LOWER(actor_name) LIKE ? OR LOWER(action) LIKE ? "
                + "OR LOWER(details) LIKE ? ORDER BY id DESC", offset, limit, like, like, like);
    }

    public static long count(String search) {
        if (search == null) {
            return Database.scalarLong("SELECT COUNT(*) FROM activity_log");
        }
        String like = "%" + search.replace("%", "").replace("_", "").toLowerCase() + "%";
        return Database.scalarLong("SELECT COUNT(*) FROM activity_log WHERE LOWER(actor_name) LIKE ? OR LOWER(action) LIKE ? "
                + "OR LOWER(details) LIKE ?", like, like, like);
    }
}
