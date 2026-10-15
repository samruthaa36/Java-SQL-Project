package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Admin-configurable rules, stored in the settings table and cached in memory. */
public final class Settings {

    private static final class Def {
        final String key;
        final String def;
        final String type; // int | bool | text
        final int min;
        final int max;
        final String label;
        final String description;

        Def(String key, String def, String type, int min, int max, String label, String description) {
            this.key = key;
            this.def = def;
            this.type = type;
            this.min = min;
            this.max = max;
            this.label = label;
            this.description = description;
        }
    }

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    static {
        add("org_name", "City Service Center", "text", 2, 60, "Organisation name",
                "Shown in the header of every page and on the public display board.");
        add("crowd_low_max", "5", "int", 0, 1000, "LOW crowd up to (people)",
                "Queue length up to this value is LOW (green).");
        add("crowd_moderate_max", "15", "int", 1, 1000, "MODERATE crowd up to (people)",
                "Queue length up to this value is MODERATE (yellow).");
        add("crowd_high_max", "30", "int", 2, 1000, "HIGH crowd up to (people)",
                "Queue length up to this value is HIGH (orange). Anything above is CRITICAL (red).");
        add("approach_threshold", "2", "int", 1, 10, "Notify when people ahead is",
                "A customer is notified when this many people (or fewer) are ahead of them.");
        add("no_show_minutes", "3", "int", 1, 30, "No-show timeout (minutes)",
                "A called customer who does not reach the counter in this time is skipped automatically.");
        add("priority_burst", "3", "int", 1, 20, "Priority burst limit",
                "After this many priority tokens in a row, one normal token is served (prevents starvation).");
        add("max_queue_size", "100", "int", 5, 1000, "Maximum queue size per service",
                "New tokens are refused when a service already has this many people waiting.");
        add("auto_assign", "false", "bool", 0, 1, "Auto-assign tokens to free counters",
                "When ON, an idle open counter is automatically given the next token of its service.");
        add("allow_registration", "true", "bool", 0, 1, "Allow public registration",
                "When OFF, only an administrator can create new customer accounts.");
        add("session_hours", "12", "int", 1, 168, "Session length (hours)",
                "How long a login stays valid when 'Remember me' is not ticked.");
    }

    private Settings() {
    }

    private static void add(String key, String def, String type, int min, int max, String label, String desc) {
        DEFS.put(key, new Def(key, def, type, min, max, label, desc));
    }

    /** Inserts missing default rows and loads everything into the cache. */
    public static void load() {
        for (Def d : DEFS.values()) {
            Row existing = Database.queryOne("SELECT setting_value FROM settings WHERE setting_key = ?", d.key);
            if (existing == null) {
                Database.update("INSERT INTO settings (setting_key, setting_value, description) VALUES (?,?,?)",
                        d.key, d.def, d.description);
            }
        }
        reload();
    }

    private static void reload() {
        CACHE.clear();
        for (Row r : Database.query("SELECT setting_key, setting_value FROM settings")) {
            CACHE.put(r.str("settingKey"), r.str("settingValue"));
        }
    }

    public static String get(String key) {
        String v = CACHE.get(key);
        if (v != null) {
            return v;
        }
        Def d = DEFS.get(key);
        return d == null ? null : d.def;
    }

    public static int getInt(String key) {
        try {
            return Integer.parseInt(get(key));
        } catch (RuntimeException e) {
            return Integer.parseInt(DEFS.get(key).def);
        }
    }

    public static boolean getBool(String key) {
        return "true".equalsIgnoreCase(get(key));
    }

    /** Settings with metadata for the admin screen. */
    public static List<Row> describe() {
        List<Row> out = new ArrayList<>();
        for (Def d : DEFS.values()) {
            out.add(new Row()
                    .with("key", d.key)
                    .with("value", get(d.key))
                    .with("type", d.type)
                    .with("min", d.min)
                    .with("max", d.max)
                    .with("label", d.label)
                    .with("description", d.description));
        }
        return out;
    }

    /** Public subset (no authentication needed). */
    public static Row publicInfo() {
        return new Row()
                .with("orgName", get("org_name"))
                .with("allowRegistration", getBool("allow_registration"))
                .with("crowdLowMax", getInt("crowd_low_max"))
                .with("crowdModerateMax", getInt("crowd_moderate_max"))
                .with("crowdHighMax", getInt("crowd_high_max"))
                .with("noShowMinutes", getInt("no_show_minutes"))
                .with("approachThreshold", getInt("approach_threshold"));
    }

    public static void update(Map<String, Object> changes, Row actor) {
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            Def d = DEFS.get(e.getKey());
            if (d == null) {
                throw ApiException.badRequest("Unknown setting: " + e.getKey());
            }
            clean.put(d.key, normalise(d, e.getValue()));
        }
        // cross-validation of crowd thresholds using the new + existing values
        int low = intOf(clean, "crowd_low_max");
        int mod = intOf(clean, "crowd_moderate_max");
        int high = intOf(clean, "crowd_high_max");
        if (!(low < mod && mod < high)) {
            throw ApiException.badRequest("Crowd limits must increase: LOW < MODERATE < HIGH.");
        }
        Database.tx(() -> {
            for (Map.Entry<String, String> e : clean.entrySet()) {
                Database.update("UPDATE settings SET setting_value = ? WHERE setting_key = ?", e.getValue(), e.getKey());
            }
            return null;
        });
        reload();
        ActivityLog.log(actor, "SETTINGS_UPDATED", "settings", null, "Changed: " + String.join(", ", clean.keySet()));
    }

    private static int intOf(Map<String, String> overrides, String key) {
        String v = overrides.get(key);
        if (v != null) {
            return Integer.parseInt(v);
        }
        return getInt(key);
    }

    private static String normalise(Def d, Object raw) {
        if (raw == null) {
            throw ApiException.badRequest(d.label + " cannot be empty.");
        }
        String s = raw.toString().trim();
        switch (d.type) {
            case "int":
                try {
                    int n = Integer.parseInt(s);
                    if (n < d.min || n > d.max) {
                        throw ApiException.badRequest(d.label + " must be between " + d.min + " and " + d.max + ".");
                    }
                    return String.valueOf(n);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest(d.label + " must be a whole number.");
                }
            case "bool":
                if (s.equalsIgnoreCase("true") || s.equals("1")) {
                    return "true";
                }
                if (s.equalsIgnoreCase("false") || s.equals("0")) {
                    return "false";
                }
                throw ApiException.badRequest(d.label + " must be true or false.");
            default:
                if (s.length() < d.min || s.length() > d.max) {
                    throw ApiException.badRequest(d.label + " must be " + d.min + "-" + d.max + " characters.");
                }
                return s;
        }
    }
}
