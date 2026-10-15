package com.smartqueue.service;

import com.smartqueue.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Periodic housekeeping: no-show handling, auto-assignment, notifications, expired data. */
public final class BackgroundJobs {

    private static ScheduledExecutorService scheduler;

    private BackgroundJobs() {
    }

    private static Runnable safe(String name, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.error("Background job '" + name + "' failed", t);
            }
        };
    }

    public static void start() {
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "smartqueue-jobs");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(safe("expire-stale", QueueService::expireStale), 1, 300, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(safe("no-shows", QueueService::autoSkipNoShows), 5, 10, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(safe("refresh", QueueService::refreshAll), 7, 10, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(safe("sessions", AuthService::purgeExpiredSessions), 60, 3600, TimeUnit.SECONDS);
    }

    public static void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
