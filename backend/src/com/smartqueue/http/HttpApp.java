package com.smartqueue.http;

import com.smartqueue.api.AdminApi;
import com.smartqueue.api.AuthApi;
import com.smartqueue.api.PublicApi;
import com.smartqueue.api.StaffApi;
import com.smartqueue.api.UserApi;
import com.smartqueue.db.DbException;
import com.smartqueue.db.Row;
import com.smartqueue.service.AuthService;
import com.smartqueue.util.Json;
import com.smartqueue.util.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** HTTP server: JSON API under /api, static front end for everything else. */
public final class HttpApp {

    private static final String CSRF_HEADER = "X-Requested-With";
    private static final String CSRF_VALUE = "SmartQueue";
    private static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("js", "text/javascript; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("webp", "image/webp");
        MIME.put("ico", "image/x-icon");
        MIME.put("txt", "text/plain; charset=utf-8");
        MIME.put("woff2", "font/woff2");
        MIME.put("woff", "font/woff");
    }

    private final Router router = new Router();
    private final Path frontendDir;
    private HttpServer server;

    public HttpApp(Path frontendDir) {
        this.frontendDir = frontendDir.toAbsolutePath().normalize();
        AuthApi.register(router);
        PublicApi.register(router);
        UserApi.register(router);
        StaffApi.register(router);
        AdminApi.register(router);
    }

    public void start(String host, int port, int threads) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 256);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, threads)));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    // ------------------------------------------------------------------
    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getRawPath();
            if (path.equals("/api") || path.startsWith("/api/")) {
                handleApi(ex);
            } else {
                handleStatic(ex, path);
            }
        } catch (Throwable t) {
            Log.error("Unhandled error", t);
            try {
                sendJson(ex, new Ctx(ex), 500, errorBody("Unexpected server error."));
            } catch (Throwable ignored) {
                // nothing more we can do
            }
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------
    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private void handleApi(HttpExchange ex) throws IOException {
        Ctx c = new Ctx(ex);
        try {
            boolean[] otherMethod = {false};
            Router.Match m = router.find(c.method, c.path, otherMethod);
            if (m == null) {
                if (c.method.equals("OPTIONS")) {
                    ex.getResponseHeaders().set("Allow", "GET, POST, PUT, DELETE");
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                throw otherMethod[0] ? new ApiException(405, "Method not allowed.") : ApiException.notFound("Unknown API endpoint.");
            }
            c.params.putAll(m.params);
            authenticate(c);

            boolean bearer = c.header("Authorization") != null && c.header("Authorization").startsWith("Bearer ");
            if (!c.method.equals("GET") && !c.method.equals("HEAD") && !bearer && !CSRF_VALUE.equals(c.header(CSRF_HEADER))) {
                throw ApiException.forbidden("Missing request header.");
            }
            checkAccess(c, m.route.access);

            Object result = m.route.handler.handle(c);
            if (result instanceof Raw) {
                sendRaw(ex, c, (Raw) result);
            } else {
                sendJson(ex, c, 200, result == null ? okBody() : result);
            }
        } catch (ApiException e) {
            sendJson(ex, c, e.status, errorBody(e.getMessage()));
        } catch (DbException e) {
            Log.error("Database error on " + c.method + " " + c.path, e);
            sendJson(ex, c, 500, errorBody("A database error occurred. Please try again."));
        } catch (Exception e) {
            Log.error("Error on " + c.method + " " + c.path, e);
            sendJson(ex, c, 500, errorBody("Unexpected server error."));
        }
    }

    private static Map<String, Object> okBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private void authenticate(Ctx c) {
        String auth = c.header("Authorization");
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7).trim();
        } else {
            token = c.cookie(AuthService.SESSION_COOKIE);
            c.cookieAuth = token != null;
        }
        c.sessionToken = token;
        if (token != null && !token.isEmpty()) {
            Row user = AuthService.authenticate(token);
            c.user = user;
        }
    }

    private void checkAccess(Ctx c, Router.Access access) {
        if (access == Router.Access.PUBLIC) {
            return;
        }
        if (c.user == null) {
            throw ApiException.unauthorized("Please sign in to continue.");
        }
        String role = c.user.str("role");
        boolean ok;
        switch (access) {
            case AUTH:
                ok = true;
                break;
            case USER:
                ok = "USER".equals(role);
                break;
            case STAFF:
                ok = "STAFF".equals(role);
                break;
            case STAFF_ADMIN:
                ok = "STAFF".equals(role) || "ADMIN".equals(role);
                break;
            case ADMIN:
                ok = "ADMIN".equals(role);
                break;
            default:
                ok = false;
        }
        if (!ok) {
            throw ApiException.forbidden("Your account is not allowed to do this.");
        }
    }

    private void applyCommonHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "same-origin");
    }

    private void sendJson(HttpExchange ex, Ctx c, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        applyCommonHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        for (String cookie : c.setCookies) {
            ex.getResponseHeaders().add("Set-Cookie", cookie);
        }
        if (c.method.equals("HEAD")) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendRaw(HttpExchange ex, Ctx c, Raw raw) throws IOException {
        applyCommonHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", raw.contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (raw.filename != null) {
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + raw.filename.replace("\"", "") + "\"");
        }
        ex.sendResponseHeaders(200, raw.data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(raw.data);
        }
    }

    // ------------------------------------------------------------------
    // Static files
    // ------------------------------------------------------------------
    private void handleStatic(HttpExchange ex, String rawPath) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD");
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String path = URLDecoder.decode(rawPath, "UTF-8");
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        Path file = frontendDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(frontendDir)) {
            sendText(ex, 403, "Forbidden");
            return;
        }
        if (Files.isDirectory(file)) {
            file = file.resolve("index.html");
        }
        if (!Files.isRegularFile(file)) {
            sendText(ex, 404, "Page not found");
            return;
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        String type = MIME.getOrDefault(ext, "application/octet-stream");
        byte[] bytes = Files.readAllBytes(file);
        applyCommonHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        if (ext.equals("html")) {
            ex.getResponseHeaders().set("Content-Security-Policy", CSP);
        }
        if (method.equals("HEAD")) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        applyCommonHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
