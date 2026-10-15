package com.smartqueue.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * All timestamps are stored as DATETIME in the server's local clock (no time-zone
 * conversion inside the database layer). They are converted to UTC instants only
 * when sent to the browser, so every client shows the correct local time.
 */
public final class Times {

    private static volatile ZoneId zone = ZoneId.systemDefault();

    private Times() {
    }

    public static void setZone(ZoneId z) {
        zone = z;
    }

    public static ZoneId zone() {
        return zone;
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(zone).withNano(0);
    }

    public static LocalDate today() {
        return LocalDate.now(zone);
    }

    public static String toIso(LocalDateTime t) {
        return t.atZone(zone).toInstant().toString();
    }

    /** Minutes (fractional) from a to b. Negative if b is before a. */
    public static double minutes(LocalDateTime a, LocalDateTime b) {
        return Duration.between(a, b).getSeconds() / 60.0;
    }

    public static long seconds(LocalDateTime a, LocalDateTime b) {
        return Duration.between(a, b).getSeconds();
    }
}
