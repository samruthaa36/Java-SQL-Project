package com.smartqueue.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Tiny console logger. */
public final class Log {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {
    }

    public static void info(String msg) {
        System.out.println(LocalDateTime.now().format(FMT) + "  INFO  " + msg);
    }

    public static void warn(String msg) {
        System.out.println(LocalDateTime.now().format(FMT) + "  WARN  " + msg);
    }

    public static void error(String msg, Throwable t) {
        System.err.println(LocalDateTime.now().format(FMT) + "  ERROR " + msg);
        if (t != null) {
            t.printStackTrace();
        }
    }
}
