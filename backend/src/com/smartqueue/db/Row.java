package com.smartqueue.db;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;

/**
 * One database row (column names converted to camelCase) with typed, null-safe getters.
 * It is also a Map, so it can be serialized to JSON directly.
 */
public class Row extends LinkedHashMap<String, Object> {

    private static final long serialVersionUID = 1L;

    public Row() {
        super();
    }

    public String str(String key) {
        Object v = get(key);
        return v == null ? null : v.toString();
    }

    public boolean isNull(String key) {
        return get(key) == null;
    }

    public long lng(String key) {
        Object v = get(key);
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1L : 0L;
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public Long lngOrNull(String key) {
        return get(key) == null ? null : Long.valueOf(lng(key));
    }

    public int integer(String key) {
        return (int) lng(key);
    }

    public double dbl(String key) {
        Object v = get(key);
        if (v == null) {
            return 0.0;
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).doubleValue();
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public boolean bool(String key) {
        Object v = get(key);
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        String s = v.toString().trim();
        return s.equalsIgnoreCase("true") || s.equals("1");
    }

    public LocalDateTime time(String key) {
        Object v = get(key);
        return v instanceof LocalDateTime ? (LocalDateTime) v : null;
    }

    public LocalDate date(String key) {
        Object v = get(key);
        return v instanceof LocalDate ? (LocalDate) v : null;
    }

    /** Fluent put. */
    public Row with(String key, Object value) {
        put(key, value);
        return this;
    }

    /** Shallow copy that keeps the Row type. */
    public Row copy() {
        Row r = new Row();
        r.putAll(this);
        return r;
    }
}
