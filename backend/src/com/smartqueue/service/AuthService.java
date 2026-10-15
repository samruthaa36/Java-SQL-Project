package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.http.ApiException;
import com.smartqueue.util.Passwords;
import com.smartqueue.util.Times;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registration, login, sessions and profile management. */
public final class AuthService {

    public static final String SESSION_COOKIE = "sq_session";
    private static final int REMEMBER_DAYS = 30;

    // Brute-force protection: 5 failed logins within 10 minutes lock the account for 5 minutes.
    private static final int MAX_FAILURES = 5;
    private static final long FAIL_WINDOW_MS = 10 * 60 * 1000L;
    private static final long LOCK_MS = 5 * 60 * 1000L;
    private static final Map<String, Deque<Long>> FAILURES = new ConcurrentHashMap<>();

    // verified against when the e-mail is unknown, so response time does not reveal which e-mails exist
    private static final String DUMMY_HASH = Passwords.hash("not-a-real-password-1");

    private AuthService() {
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------
    public static Row register(String fullName, String email, String phone, String password) {
        return register(fullName, email, phone, password, "USER", false, null);
    }

    public static Row register(String fullName, String email, String phone, String password,
                               String role, boolean priority, String priorityReason) {
        if (!Settings.getBool("allow_registration")) {
            throw ApiException.forbidden("Public registration is turned off. Please contact the administrator.");
        }
        String name = Validate.text(fullName, "Full name", 2, 100);
        String mail = Validate.email(email);
        String ph = Validate.phone(phone);
        Validate.password(password);
        String r = role != null && !role.trim().isEmpty() ? role.trim().toUpperCase() : "USER";
        if (!"ADMIN".equals(r) && !"STAFF".equals(r) && !"USER".equals(r)) {
            r = "USER";
        }
        return createUser(name, mail, ph, password, r, priority, priorityReason, null);
    }

    /** Used by registration and by administrators. */
    public static Row createUser(String name, String email, String phone, String password, String role,
                                 boolean priority, String priorityReason, Row actor) {
        if (Database.scalarLong("SELECT COUNT(*) FROM users WHERE email = ?", email) > 0) {
            throw ApiException.conflict("An account with this email already exists.");
        }
        String hash = Passwords.hash(password);
        long id = Database.insert("INSERT INTO users (id, full_name, email, phone, password_hash, role, is_priority, priority_reason, "
                        + "is_active, created_at) VALUES (users_seq.NEXTVAL, ?,?,?,?,?,?,?,1,?)",
                name, email, phone, hash, role, priority ? 1 : 0, priority ? priorityReason : null, Times.now());
        Row user = Database.queryOne("SELECT * FROM users WHERE id = ?", id);
        if ("USER".equals(role)) {
            NotificationService.notify(id, null, "WELCOME", "Welcome to SmartQueue",
                    "Skip the line, not the service. Pick a service to get your digital token.");
        }
        if (actor != null) {
            ActivityLog.log(actor, "USER_CREATED", "user", id, role + " account created for " + email);
        }
        return publicUser(user);
    }

    // ------------------------------------------------------------------
    // Login / sessions
    // ------------------------------------------------------------------
    public static final class LoginResult {
        public final Row user;
        public final String token;
        public final int maxAgeSeconds;

        LoginResult(Row user, String token, int maxAgeSeconds) {
            this.user = user;
            this.token = token;
            this.maxAgeSeconds = maxAgeSeconds;
        }
    }

    public static LoginResult login(String email, String password, boolean remember, String ip, String userAgent) {
        String mail = email == null ? "" : email.trim().toLowerCase();
        if (mail.isEmpty() || password == null || password.isEmpty()) {
            throw ApiException.badRequest("Please enter your email and password.");
        }
        checkLocked(mail);
        Row user = Database.queryOne("SELECT * FROM users WHERE email = ?", mail);
        boolean ok;
        if (user == null) {
            Passwords.verify(password, DUMMY_HASH);
            ok = false;
        } else {
            ok = Passwords.verify(password, user.str("passwordHash"));
        }
        if (!ok) {
            recordFailure(mail);
            throw ApiException.unauthorized("Invalid email or password.");
        }
        if (!user.bool("isActive")) {
            throw ApiException.forbidden("This account has been deactivated. Please contact the administrator.");
        }
        FAILURES.remove(mail);

        LocalDateTime now = Times.now();
        String raw = Passwords.newSessionToken();
        int maxAge = remember ? REMEMBER_DAYS * 24 * 3600 : Settings.getInt("session_hours") * 3600;
        Database.update("INSERT INTO sessions (token_hash, user_id, created_at, expires_at, ip, user_agent) VALUES (?,?,?,?,?,?)",
                Passwords.sha256Hex(raw), user.lng("id"), now, now.plusSeconds(maxAge), ip,
                userAgent == null ? null : (userAgent.length() > 200 ? userAgent.substring(0, 200) : userAgent));
        Database.update("UPDATE users SET last_login_at = ? WHERE id = ?", now, user.lng("id"));
        return new LoginResult(publicUser(user), raw, remember ? maxAge : -1);
    }

    private static void checkLocked(String mail) {
        Deque<Long> q = FAILURES.get(mail);
        if (q == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > FAIL_WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= MAX_FAILURES && now - q.peekLast() < LOCK_MS) {
                long wait = (LOCK_MS - (now - q.peekLast())) / 1000 + 1;
                throw ApiException.tooMany("Too many failed attempts. Try again in " + ((wait + 59) / 60) + " minute(s).");
            }
        }
    }

    private static void recordFailure(String mail) {
        Deque<Long> q = FAILURES.computeIfAbsent(mail, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(System.currentTimeMillis());
            while (q.size() > MAX_FAILURES + 5) {
                q.pollFirst();
            }
        }
    }

    /** Resolves a raw session token to the (active) user, or null. */
    public static Row authenticate(String rawToken) {
        if (rawToken == null || rawToken.length() < 20 || rawToken.length() > 100) {
            return null;
        }
        return Database.queryOne("SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id "
                + "WHERE s.token_hash = ? AND s.expires_at > ? AND u.is_active = 1", Passwords.sha256Hex(rawToken), Times.now());
    }

    public static void logout(String rawToken) {
        if (rawToken != null) {
            Database.update("DELETE FROM sessions WHERE token_hash = ?", Passwords.sha256Hex(rawToken));
        }
    }

    public static void purgeExpiredSessions() {
        Database.update("DELETE FROM sessions WHERE expires_at < ?", Times.now());
    }

    // ------------------------------------------------------------------
    // Profile
    // ------------------------------------------------------------------

    /** Strips the password hash before a user row leaves the server. */
    public static Row publicUser(Row u) {
        Row r = new Row();
        r.put("id", u.lng("id"));
        r.put("fullName", u.str("fullName"));
        r.put("email", u.str("email"));
        r.put("phone", u.str("phone"));
        r.put("role", u.str("role"));
        r.put("isPriority", u.bool("isPriority"));
        r.put("priorityReason", u.str("priorityReason"));
        r.put("isActive", u.bool("isActive"));
        r.put("createdAt", u.time("createdAt"));
        r.put("lastLoginAt", u.time("lastLoginAt"));
        return r;
    }

    public static Row updateProfile(long userId, String fullName, String phone) {
        String name = Validate.text(fullName, "Full name", 2, 100);
        String ph = Validate.phone(phone);
        Database.update("UPDATE users SET full_name = ?, phone = ? WHERE id = ?", name, ph, userId);
        return publicUser(Database.queryOne("SELECT * FROM users WHERE id = ?", userId));
    }

    public static void changePassword(Row user, String current, String next, String currentSessionToken) {
        if (!Passwords.verify(current, user.str("passwordHash"))) {
            throw ApiException.badRequest("Your current password is incorrect.");
        }
        Validate.password(next);
        if (current.equals(next)) {
            throw ApiException.badRequest("Choose a new password that is different from the current one.");
        }
        long id = user.lng("id");
        Database.update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(next), id);
        // sign out every other device
        if (currentSessionToken != null) {
            Database.update("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?", id, Passwords.sha256Hex(currentSessionToken));
        } else {
            Database.update("DELETE FROM sessions WHERE user_id = ?", id);
        }
    }
}
