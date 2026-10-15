package com.smartqueue.db;

import com.smartqueue.service.ActivityLog;
import com.smartqueue.util.Log;
import com.smartqueue.util.Passwords;
import com.smartqueue.util.Times;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * First-start data: default accounts, services, counters and (optionally) 30 days of simulated history
 * so the analytics, charts and the waiting-time prediction have real-looking data from the beginning.
 *
 * Demo logins (change them!):
 *   admin@smartqueue.com / Admin@123      staff: ravi@smartqueue.com / Staff@123      customer: user@smartqueue.com / User@123
 */
public final class DemoDataSeeder {

    private DemoDataSeeder() {
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------
    public static void seedIfEmpty(boolean withDemoData) {
        ensureServicesAndCounters();
        if (!withDemoData) {
            purgeDemoDataIfExists();
            return;
        }
        if (Database.scalarLong("SELECT COUNT(*) FROM users") > 0) {
            return;
        }
        Log.info("First start: creating demo accounts, services and 30 days of sample history ...");
        long t0 = System.currentTimeMillis();
        Database.tx(() -> {
            seed();
            return null;
        });
        Log.info("Demo data ready in " + (System.currentTimeMillis() - t0) / 1000.0 + " s.");
    }

    public static void ensureServicesAndCounters() {
        if (Database.scalarLong("SELECT COUNT(*) FROM services") > 0) {
            return;
        }
        Log.info("Initializing services and counters...");
        Database.tx(() -> {
            long[] serviceIds = new long[SERVICES.length];
            for (int i = 0; i < SERVICES.length; i++) {
                ServiceDef s = SERVICES[i];
                serviceIds[i] = Database.insert("INSERT INTO services (id, name, prefix, description, icon, color, avg_service_minutes, is_active, created_at) "
                        + "VALUES (services_seq.NEXTVAL, ?,?,?,?,?,?,1,?)", s.name, s.prefix, s.description, s.icon, s.color, s.avgMinutes, Times.now());
            }
            for (int i = 0; i < SERVICES.length; i++) {
                for (int ci : SERVICES[i].counterIdx) {
                    Database.insert("INSERT INTO counters (id, name, service_id, status, is_deleted, created_at) VALUES (counters_seq.NEXTVAL, ?,?,'CLOSED',0,?)",
                            COUNTER_NAMES[ci], serviceIds[i], Times.now());
                }
            }
            return null;
        });
    }

    public static void purgeDemoDataIfExists() {
        try {
            long demoUsers = Database.scalarLong("SELECT COUNT(*) FROM users WHERE email LIKE '%@smartqueue.com' OR email LIKE '%@example.com'");
            long demoTokens = Database.scalarLong("SELECT COUNT(*) FROM tokens WHERE notes = 'Demo traffic' OR customer_phone LIKE '98450%' OR token_date < TRUNC(SYSDATE)");
            if (demoUsers > 0 || demoTokens > 0) {
                Log.info("Removing demo records and fake history from Oracle database...");
                Database.tx(() -> {
                    long realUsers = Database.scalarLong("SELECT COUNT(*) FROM users WHERE email NOT LIKE '%@smartqueue.com' AND email NOT LIKE '%@example.com'");
                    if (realUsers == 0) {
                        Database.update("DELETE FROM tokens");
                    } else {
                        Database.update("DELETE FROM tokens WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@smartqueue.com' OR email LIKE '%@example.com') OR customer_phone LIKE '98450%' OR notes = 'Demo traffic' OR token_date < TRUNC(SYSDATE)");
                    }
                    Database.update("DELETE FROM notifications WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@smartqueue.com' OR email LIKE '%@example.com')");
                    Database.update("DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@smartqueue.com' OR email LIKE '%@example.com')");
                    Database.update("UPDATE counters SET staff_id = NULL, status = 'CLOSED'");
                    Database.update("DELETE FROM users WHERE email LIKE '%@smartqueue.com' OR email LIKE '%@example.com'");
                    Database.update("DELETE FROM activity_log WHERE details LIKE '%demo%' OR details LIKE '%Demo%' OR action IN ('SYSTEM_INIT', 'DEMO_CROWD')");
                    return null;
                });
                Log.info("Oracle database cleaned: only real data will be used.");
            }
        } catch (Exception e) {
            Log.warn("Purge check: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private static long insertUser(String name, String email, String phone, String password, String role, boolean priority, String reason) {
        return Database.insert("INSERT INTO users (id, full_name, email, phone, password_hash, role, is_priority, priority_reason, is_active, created_at) "
                        + "VALUES (users_seq.NEXTVAL, ?,?,?,?,?,?,?,1,?)", name, email, phone, Passwords.hash(password), role, priority ? 1 : 0,
                reason, Times.now().minusDays(45));
    }

    private static final String[] FIRST = {"Aarav", "Diya", "Vihaan", "Ananya", "Rohan", "Meera", "Kabir", "Isha", "Arjun", "Sneha", "Rahul",
            "Pooja", "Karan", "Nisha", "Siddharth", "Lakshmi", "Manish", "Tara", "Varun", "Aditi", "Harini", "Gokul", "Swathi", "Naveen",
            "Priya", "Suresh", "Deepa", "Vignesh", "Kavya", "Balaji"};
    private static final String[] LAST = {"Sharma", "Patel", "Reddy", "Iyer", "Gupta", "Nair", "Singh", "Menon", "Das", "Rao", "Verma",
            "Joshi", "Mehta", "Kapoor", "Bose", "Pillai", "Kumar", "Banerjee", "Chopra", "Desai", "Krishnan", "Raj", "Subramanian"};
    private static final String[] COMMENTS = {"Quick and helpful service.", "Staff were polite and efficient.", "Waiting time matched the estimate.",
            "Very smooth experience.", "Could be a little faster.", "Great that I did not have to stand in line!"};

    private static final class ServiceDef {
        final String prefix;
        final String name;
        final String description;
        final String icon;
        final String color;
        final double avgMinutes;
        final int dailyBase;
        final int[] counterIdx;

        ServiceDef(String prefix, String name, String description, String icon, String color, double avgMinutes, int dailyBase, int... counterIdx) {
            this.prefix = prefix;
            this.name = name;
            this.description = description;
            this.icon = icon;
            this.color = color;
            this.avgMinutes = avgMinutes;
            this.dailyBase = dailyBase;
            this.counterIdx = counterIdx;
        }
    }

    private static final ServiceDef[] SERVICES = {
            new ServiceDef("A", "General Services", "Applications, certificates and everyday requests", "users", "#4f46e5", 5.0, 115, 0, 1),
            new ServiceDef("B", "Accounts & Billing", "Payments, invoices and account queries", "wallet", "#0d9488", 8.0, 62, 2, 5),
            new ServiceDef("C", "Enquiry Desk", "Information, guidance and complaints", "help", "#d97706", 4.0, 55, 3),
            new ServiceDef("D", "Document Services", "Verification, attestation and collection", "file", "#e11d48", 10.0, 18, 4)
    };

    private static final String[] COUNTER_NAMES = {"Counter 1", "Counter 2", "Counter 3", "Counter 4", "Counter 5", "Counter 6"};
    private static final String[] STAFF_NAMES = {"Ravi Kumar", "Meena Iyer", "Arjun Nair", "Divya Menon", "Karthik Raj", "Sana Fathima"};
    private static final String[] STAFF_MAIL = {"ravi@smartqueue.com", "meena@smartqueue.com", "arjun@smartqueue.com", "divya@smartqueue.com",
            "karthik@smartqueue.com", "sana@smartqueue.com"};
    private static final double[] STAFF_SPEED = {0.95, 0.90, 1.10, 1.00, 1.20, 0.92};
    private static final double[] STAFF_RATING = {4.5, 4.7, 4.0, 4.3, 3.8, 4.6};

    private static final double[] HOUR_WEIGHT = new double[24];
    private static final double[] WEEKDAY_FACTOR = {1.25, 1.0, 0.9, 1.0, 1.15, 0.55, 0.0}; // Mon..Sun

    static {
        double[] w = {0.09, 0.14, 0.17, 0.12, 0.07, 0.13, 0.14, 0.09, 0.05}; // 09:00 .. 17:00
        for (int i = 0; i < w.length; i++) {
            HOUR_WEIGHT[9 + i] = w[i];
        }
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------
    private static void seed() {
        Random rnd = new Random(2026);

        // --- users
        long adminId = insertUser("System Administrator", "admin@smartqueue.com", "9000000001", "Admin@123", "ADMIN", false, null);
        long[] staffIds = new long[STAFF_NAMES.length];
        for (int i = 0; i < STAFF_NAMES.length; i++) {
            staffIds[i] = insertUser(STAFF_NAMES[i], STAFF_MAIL[i], "90000001" + (10 + i), "Staff@123", "STAFF", false, null);
        }
        List<Long> customerIds = new ArrayList<>();
        List<String> customerNames = new ArrayList<>();
        long demoUser = insertUser("Demo User", "user@smartqueue.com", "9876543210", "User@123", "USER", false, null);
        customerIds.add(demoUser);
        customerNames.add("Demo User");
        long senior = insertUser("Senior Citizen Demo", "senior@smartqueue.com", "9876543211", "User@123", "USER", true, "Senior citizen");
        customerIds.add(senior);
        customerNames.add("Senior Citizen Demo");
        String sharedHash = Passwords.hash("User@123");
        for (int i = 0; i < 38; i++) {
            String fn = FIRST[i % FIRST.length];
            String ln = LAST[(i * 7 + 3) % LAST.length];
            String email = fn.toLowerCase() + "." + ln.toLowerCase() + (i + 1) + "@example.com";
            long id = Database.insert("INSERT INTO users (id, full_name, email, phone, password_hash, role, is_priority, priority_reason, is_active, created_at) "
                            + "VALUES (users_seq.NEXTVAL, ?,?,?,?,'USER',0,NULL,1,?)", fn + " " + ln, email, "98450" + String.format("%05d", 10000 + i * 37),
                    sharedHash, Times.now().minusDays(40 - (i % 30)));
            customerIds.add(id);
            customerNames.add(fn + " " + ln);
        }

        // --- services and counters
        long[] serviceIds = new long[SERVICES.length];
        for (int i = 0; i < SERVICES.length; i++) {
            ServiceDef s = SERVICES[i];
            serviceIds[i] = Database.insert("INSERT INTO services (id, name, prefix, description, icon, color, avg_service_minutes, is_active, created_at) "
                    + "VALUES (services_seq.NEXTVAL, ?,?,?,?,?,?,1,?)", s.name, s.prefix, s.description, s.icon, s.color, s.avgMinutes, Times.now().minusDays(45));
        }
        long[] counterIds = new long[COUNTER_NAMES.length];
        for (int i = 0; i < SERVICES.length; i++) {
            for (int ci : SERVICES[i].counterIdx) {
                counterIds[ci] = Database.insert("INSERT INTO counters (id, name, service_id, status, is_deleted, created_at) VALUES (counters_seq.NEXTVAL, ?,?,'CLOSED',0,?)",
                        COUNTER_NAMES[ci], serviceIds[i], Times.now().minusDays(45));
            }
        }

        // --- history
        LocalDate today = Times.today();
        LocalDateTime now = Times.now();
        List<Object[]> batch = new ArrayList<>();
        int totalTokens = 0;
        for (int offset = 30; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            double factor = WEEKDAY_FACTOR[day.getDayOfWeek().getValue() - 1];
            if (factor == 0.0) {
                continue;
            }
            long nowSec = offset == 0 ? (now.getHour() * 3600L + now.getMinute() * 60L + now.getSecond()) : Long.MAX_VALUE;
            for (int si = 0; si < SERVICES.length; si++) {
                totalTokens += simulateDay(rnd, day, nowSec, factor * (1.0 + (30 - offset) * 0.004), si, serviceIds[si], counterIds, staffIds,
                        customerIds, customerNames, batch);
            }
        }
        Database.batch("INSERT INTO tokens (id, service_id, token_seq, token_code, token_date, user_id, customer_name, customer_phone, priority, "
                + "priority_reason, status, source, counter_id, issued_by, served_by, notes, created_at, queue_time, called_at, last_called_at, "
                + "serving_at, ended_at, service_seconds, call_count, estimated_wait_min, approach_notified, next_notified, close_reason, rating, "
                + "feedback) VALUES (tokens_seq.NEXTVAL, ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,1,?,?,?)", batch);

        // a welcome notification for the demo accounts
        Database.update("INSERT INTO notifications (id, user_id, token_id, type, title, message, is_read, created_at) VALUES (notifications_seq.NEXTVAL, ?,?,?,?,?,0,?)",
                demoUser, null, "WELCOME", "Welcome to SmartQueue", "Skip the line, not the service. Pick a service to get your digital token.", Times.now());
        Database.update("INSERT INTO notifications (id, user_id, token_id, type, title, message, is_read, created_at) VALUES (notifications_seq.NEXTVAL, ?,?,?,?,?,0,?)",
                senior, null, "WELCOME", "Welcome to SmartQueue", "Your account has priority access - your tokens are called first.", Times.now());
        ActivityLog.log(null, "SYSTEM_INIT", "user", adminId, "Demo data created: " + totalTokens + " historical tokens");
    }

    // ------------------------------------------------------------------
    // Simulation of one service on one day (single-queue, multi-counter, priority first)
    // ------------------------------------------------------------------
    private static final class Sim {
        int priority;
        long arrival;
        Long userId;
        String name;
        String phone;
        boolean walkIn;
        long cancelAfter = -1;
        boolean noShow;
        String status = "COMPLETED";
        long calledAt = -1;
        long servingAt = -1;
        long endedAt = -1;
        int counterSlot = -1;
        int serviceSeconds;
        String closeReason;
        String reason;
    }

    private static double gaussian(Random r) {
        return r.nextGaussian();
    }

    private static int simulateDay(Random rnd, LocalDate day, long nowSec, double factor, int si, long serviceId, long[] counterIds,
                                   long[] staffIds, List<Long> customerIds, List<String> customerNames, List<Object[]> out) {
        ServiceDef sd = SERVICES[si];
        int n = (int) Math.round(sd.dailyBase * factor * (1.0 + gaussian(rnd) * 0.08));
        if (n < 1) {
            return 0;
        }
        // arrivals
        List<Sim> sims = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x = rnd.nextDouble();
            int hour = 9;
            double acc = 0;
            for (int h = 9; h < 18; h++) {
                acc += HOUR_WEIGHT[h];
                if (x <= acc) {
                    hour = h;
                    break;
                }
                hour = h;
            }
            Sim s = new Sim();
            s.arrival = hour * 3600L + rnd.nextInt(3600);
            if (s.arrival > nowSec) {
                continue;
            }
            s.priority = rnd.nextDouble() < 0.07 ? 1 : 0;
            if (s.priority == 1) {
                s.reason = rnd.nextBoolean() ? "Senior citizen" : "Differently-abled";
            }
            s.walkIn = rnd.nextDouble() > 0.65;
            int cust = rnd.nextInt(customerIds.size());
            if (!s.walkIn) {
                s.userId = customerIds.get(cust);
                s.name = customerNames.get(cust);
            } else {
                s.name = FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)];
            }
            s.phone = "98" + String.format("%08d", rnd.nextInt(100000000));
            if (rnd.nextDouble() < 0.09) {
                s.cancelAfter = 180 + rnd.nextInt(1500);
            }
            s.noShow = rnd.nextDouble() < 0.05;
            sims.add(s);
        }
        sims.sort((a, b) -> Long.compare(a.arrival, b.arrival));

        // multi-counter discrete event simulation
        int k = sd.counterIdx.length;
        long[] free = new long[k];
        for (int i = 0; i < k; i++) {
            free[i] = 9 * 3600L;
        }
        List<Sim> queue = new ArrayList<>();
        int idx = 0;
        final long closeSec = 18 * 3600L + 1800;
        while (true) {
            int c = 0;
            for (int i = 1; i < k; i++) {
                if (free[i] < free[c]) {
                    c = i;
                }
            }
            long t = free[c];
            while (idx < sims.size() && sims.get(idx).arrival <= t) {
                queue.add(sims.get(idx++));
            }
            // customers who gave up
            for (int i = queue.size() - 1; i >= 0; i--) {
                Sim q = queue.get(i);
                if (q.cancelAfter >= 0 && q.arrival + q.cancelAfter <= t) {
                    q.status = "CANCELLED";
                    q.endedAt = q.arrival + q.cancelAfter;
                    q.closeReason = "Cancelled by customer";
                    queue.remove(i);
                }
            }
            if (queue.isEmpty()) {
                if (idx >= sims.size()) {
                    break;
                }
                free[c] = Math.max(free[c], sims.get(idx).arrival);
                continue;
            }
            if (t >= closeSec) {
                for (Sim q : queue) {
                    q.status = "CANCELLED";
                    q.endedAt = closeSec;
                    q.closeReason = "Expired at end of day";
                }
                queue.clear();
                if (idx >= sims.size()) {
                    break;
                }
                continue;
            }
            // highest priority first, then earliest arrival
            Sim pick = queue.get(0);
            for (Sim q : queue) {
                if (q.priority > pick.priority || (q.priority == pick.priority && q.arrival < pick.arrival)) {
                    pick = q;
                }
            }
            queue.remove(pick);
            pick.calledAt = t;
            pick.counterSlot = c;
            if (pick.noShow) {
                pick.status = "SKIPPED";
                pick.endedAt = t + 180;
                pick.closeReason = "No-show: did not reach the counter within 3 min";
                free[c] = t + 180;
            } else {
                double speed = STAFF_SPEED[sd.counterIdx[c]];
                double dur = sd.avgMinutes * 60.0 * speed * Math.exp(gaussian(rnd) * 0.35 - 0.06);
                dur = Math.max(45, Math.min(dur, sd.avgMinutes * 60.0 * 3.5));
                pick.servingAt = t + 8 + rnd.nextInt(20);
                pick.serviceSeconds = (int) Math.round(dur);
                pick.endedAt = pick.servingAt + pick.serviceSeconds;
                pick.status = "COMPLETED";
                free[c] = pick.endedAt + 8 + rnd.nextInt(15);
            }
        }
        for (Sim q : queue) {
            q.status = "CANCELLED";
            q.endedAt = closeSec;
            q.closeReason = "Expired at end of day";
        }
        // anything still unprocessed (should not happen) is cancelled
        while (idx < sims.size()) {
            Sim q = sims.get(idx++);
            q.status = "CANCELLED";
            q.endedAt = closeSec;
            q.closeReason = "Expired at end of day";
        }

        // keep only finished tokens (for "today": ended before the current time)
        List<Sim> kept = new ArrayList<>();
        for (Sim s : sims) {
            if (s.endedAt >= 0 && s.endedAt <= nowSec) {
                kept.add(s);
            }
        }
        Collections.sort(kept, (a, b) -> Long.compare(a.arrival, b.arrival));

        LocalDateTime base = day.atStartOfDay();
        int seq = 0;
        for (Sim s : kept) {
            seq++;
            String code = sd.prefix + String.format("%03d", seq);
            Long counterId = null;
            Long servedBy = null;
            if (s.counterSlot >= 0) {
                int ci = sd.counterIdx[s.counterSlot];
                counterId = counterIds[ci];
                servedBy = staffIds[ci];
            }
            Long issuedBy = s.walkIn ? Long.valueOf(staffIds[sd.counterIdx[0]]) : null;
            Integer estimated = null;
            if (s.calledAt >= 0) {
                double actualMin = (s.calledAt - s.arrival) / 60.0;
                estimated = (int) Math.max(0, Math.round(actualMin * (1 + gaussian(rnd) * 0.18) + gaussian(rnd) * 1.2));
            }
            Integer rating = null;
            String feedback = null;
            if ("COMPLETED".equals(s.status) && rnd.nextDouble() < 0.5) {
                double mean = STAFF_RATING[sd.counterIdx[s.counterSlot]];
                rating = (int) Math.max(1, Math.min(5, Math.round(mean + gaussian(rnd) * 0.7)));
                if (rnd.nextDouble() < 0.15) {
                    feedback = COMMENTS[rnd.nextInt(COMMENTS.length)];
                }
            }
            LocalDateTime created = base.plusSeconds(s.arrival);
            LocalDateTime called = s.calledAt >= 0 ? base.plusSeconds(s.calledAt) : null;
            LocalDateTime serving = s.servingAt >= 0 ? base.plusSeconds(s.servingAt) : null;
            LocalDateTime ended = base.plusSeconds(s.endedAt);
            out.add(new Object[]{serviceId, seq, code, day, s.userId, s.name, s.phone, s.priority, s.priority > 0 ? s.reason : null, s.status,
                    s.walkIn ? "WALK_IN" : "ONLINE", counterId, issuedBy, servedBy, null, created, created, called, called, serving, ended,
                    s.serviceSeconds, s.calledAt >= 0 ? 1 : 0, estimated, s.closeReason, rating, feedback});
        }
        return kept.size();
    }
}
