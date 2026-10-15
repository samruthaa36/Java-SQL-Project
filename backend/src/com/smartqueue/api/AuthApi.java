package com.smartqueue.api;

import com.smartqueue.db.Row;
import com.smartqueue.http.Ctx;
import com.smartqueue.http.Router;
import com.smartqueue.http.Router.Access;
import com.smartqueue.service.AuthService;
import com.smartqueue.service.NotificationService;
import com.smartqueue.service.Settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sign-in, registration, profile and notification endpoints (shared by all roles). */
public final class AuthApi {

    private AuthApi() {
    }

    private static String homePage(String role) {
        switch (role) {
            case "ADMIN":
                return "admin.html";
            case "STAFF":
                return "staff.html";
            default:
                return "user.html";
        }
    }

    private static Map<String, Object> signedIn(Ctx c, AuthService.LoginResult r) {
        c.addCookie(AuthService.SESSION_COOKIE, r.token, r.maxAgeSeconds);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", r.user);
        out.put("home", homePage(r.user.str("role")));
        return out;
    }

    public static void register(Router r) {
        r.post("/api/auth/register", Access.PUBLIC, c -> {
            String email = c.str("email");
            Object pw = c.body().get("password");
            String password = pw == null ? null : pw.toString(); // passwords are never trimmed
            String role = c.str("role");
            boolean priority = Boolean.TRUE.equals(c.bool("isPriority"));
            String priorityReason = c.str("priorityReason");
            AuthService.register(c.str("fullName"), email, c.str("phone"), password, role, priority, priorityReason);
            AuthService.LoginResult res = AuthService.login(email, password, false, c.ip(), c.header("User-Agent"));
            return signedIn(c, res);
        });

        r.post("/api/auth/login", Access.PUBLIC, c -> {
            Object pw = c.body().get("password");
            boolean remember = Boolean.TRUE.equals(c.bool("remember"));
            AuthService.LoginResult res = AuthService.login(c.str("email"), pw == null ? null : pw.toString(), remember,
                    c.ip(), c.header("User-Agent"));
            return signedIn(c, res);
        });

        r.post("/api/auth/logout", Access.PUBLIC, c -> {
            AuthService.logout(c.sessionToken);
            c.addCookie(AuthService.SESSION_COOKIE, "", 0);
            return null;
        });

        // like /me but never an error: lets the sign-in page check quietly whether a session already exists
        r.get("/api/auth/session", Access.PUBLIC, c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            if (c.user == null) {
                out.put("authenticated", false);
                return out;
            }
            out.put("authenticated", true);
            out.put("user", AuthService.publicUser(c.user));
            out.put("home", homePage(c.user.str("role")));
            return out;
        });

        r.get("/api/auth/me", Access.AUTH, c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", AuthService.publicUser(c.user));
            out.put("home", homePage(c.user.str("role")));
            out.put("orgName", Settings.get("org_name"));
            return out;
        });

        r.put("/api/auth/profile", Access.AUTH, c -> {
            Row u = AuthService.updateProfile(c.userId(), c.str("fullName"), c.str("phone"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", u);
            return out;
        });

        r.post("/api/auth/change-password", Access.AUTH, c -> {
            Object cur = c.body().get("currentPassword");
            Object next = c.body().get("newPassword");
            AuthService.changePassword(c.user, cur == null ? "" : cur.toString(), next == null ? "" : next.toString(), c.sessionToken);
            return null;
        });

        // ---------------- notifications (all roles)
        r.get("/api/notifications", Access.AUTH, c -> {
            List<Row> items = NotificationService.list(c.userId(), c.qInt("limit", 30, 1, 100));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", items);
            out.put("unread", NotificationService.unread(c.userId()));
            return out;
        });
        r.post("/api/notifications/read-all", Access.AUTH, c -> {
            NotificationService.markAllRead(c.userId());
            return null;
        });
        r.post("/api/notifications/{id}/read", Access.AUTH, c -> {
            NotificationService.markRead(c.userId(), c.pathId("id"));
            return null;
        });
        r.delete("/api/notifications", Access.AUTH, c -> {
            NotificationService.clearAll(c.userId());
            return null;
        });
    }
}
