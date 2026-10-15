package com.smartqueue.http;

import com.smartqueue.db.Row;
import com.smartqueue.util.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything a request handler needs: path/query params, JSON body, the signed-in user. */
public final class Ctx {

    private static final int MAX_BODY = 1_048_576;

    public final HttpExchange exchange;
    public final String method;
    public final String path;
    public final Map<String, String> query;
    public final Map<String, String> params = new HashMap<>();
    public final List<String> setCookies = new ArrayList<>();

    /** The authenticated user (null for anonymous requests). */
    public Row user;
    /** True when the session came from the cookie (so CSRF protection applies). */
    public boolean cookieAuth;
    /** The raw session token presented with this request (cookie or Bearer header), if any. */
    public String sessionToken;

    private Map<String, Object> body;

    public Ctx(HttpExchange exchange) {
        this.exchange = exchange;
        this.method = exchange.getRequestMethod().toUpperCase();
        this.path = exchange.getRequestURI().getRawPath();
        this.query = parseQuery(exchange.getRequestURI().getRawQuery());
    }

    // ------------------------------------------------------------------
    // Request info
    // ------------------------------------------------------------------
    public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    public String ip() {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    public String cookie(String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String h : headers) {
            for (String part : h.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(name)) {
                    return p.substring(eq + 1);
                }
            }
        }
        return null;
    }

    public Row requireUser() {
        if (user == null) {
            throw ApiException.unauthorized("Please sign in to continue.");
        }
        return user;
    }

    public long userId() {
        return requireUser().lng("id");
    }

    // ------------------------------------------------------------------
    // Path & query parameters
    // ------------------------------------------------------------------
    public long pathId(String name) {
        String v = params.get(name);
        try {
            long id = Long.parseLong(v);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid " + name + ".");
        }
    }

    public String q(String name) {
        String v = query.get(name);
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }

    public int qInt(String name, int def, int min, int max) {
        String v = q(name);
        if (v == null) {
            return def;
        }
        try {
            int n = Integer.parseInt(v);
            return Math.max(min, Math.min(max, n));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public Long qLong(String name) {
        String v = q(name);
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid " + name + ".");
        }
    }

    static Map<String, String> parseQuery(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            map.put(decode(k), decode(v));
        }
        return map;
    }

    static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------
    // JSON body
    // ------------------------------------------------------------------
    public Map<String, Object> body() {
        if (body != null) {
            return body;
        }
        try {
            InputStream in = exchange.getRequestBody();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) {
                    throw new ApiException(413, "Request body is too large.");
                }
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                body = Collections.emptyMap();
            } else {
                body = Json.parseObject(text);
            }
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the request body.");
        } catch (Json.JsonException e) {
            throw ApiException.badRequest("Invalid JSON: " + e.getMessage());
        }
        return body;
    }

    /** Trimmed string value or null when missing / blank. */
    public String str(String key) {
        Object v = body().get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public String reqStr(String key, String label) {
        String s = str(key);
        if (s == null) {
            throw ApiException.badRequest(label + " is required.");
        }
        return s;
    }

    public Long lng(String key) {
        Object v = body().get(key);
        if (v == null || v.toString().trim().isEmpty()) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid value for " + key + ".");
        }
    }

    public long reqLong(String key, String label) {
        Long v = lng(key);
        if (v == null) {
            throw ApiException.badRequest(label + " is required.");
        }
        return v;
    }

    public Integer integer(String key) {
        Long v = lng(key);
        return v == null ? null : Integer.valueOf(v.intValue());
    }

    public Double dbl(String key) {
        Object v = body().get(key);
        if (v == null || v.toString().trim().isEmpty()) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid value for " + key + ".");
        }
    }

    public Boolean bool(String key) {
        Object v = body().get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = v.toString().trim().toLowerCase();
        if (s.equals("true") || s.equals("1") || s.equals("yes")) {
            return Boolean.TRUE;
        }
        if (s.equals("false") || s.equals("0") || s.equals("no")) {
            return Boolean.FALSE;
        }
        throw ApiException.badRequest("Invalid value for " + key + ".");
    }

    public boolean has(String key) {
        return body().containsKey(key);
    }

    // ------------------------------------------------------------------
    // Cookies
    // ------------------------------------------------------------------
    public void addCookie(String name, String value, int maxAgeSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value).append("; Path=/; HttpOnly; SameSite=Strict");
        if (maxAgeSeconds >= 0) {
            sb.append("; Max-Age=").append(maxAgeSeconds);
        }
        setCookies.add(sb.toString());
    }
}
