package com.smartqueue.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tiny REST router with {param} path segments and per-route access control. */
public final class Router {

    public enum Access {
        /** No sign-in needed. */
        PUBLIC,
        /** Any signed-in user. */
        AUTH,
        /** Customers only. */
        USER,
        /** Counter staff only. */
        STAFF,
        /** Staff or administrators. */
        STAFF_ADMIN,
        /** Administrators only. */
        ADMIN
    }

    public interface Handler {
        Object handle(Ctx ctx) throws Exception;
    }

    public static final class Route {
        final String method;
        final String[] segments;
        final Access access;
        final Handler handler;

        Route(String method, String pattern, Access access, Handler handler) {
            this.method = method;
            this.segments = split(pattern);
            this.access = access;
            this.handler = handler;
        }
    }

    public static final class Match {
        public final Route route;
        public final Map<String, String> params;

        Match(Route route, Map<String, String> params) {
            this.route = route;
            this.params = params;
        }
    }

    private final List<Route> routes = new ArrayList<>();

    public void get(String pattern, Access a, Handler h) {
        routes.add(new Route("GET", pattern, a, h));
    }

    public void post(String pattern, Access a, Handler h) {
        routes.add(new Route("POST", pattern, a, h));
    }

    public void put(String pattern, Access a, Handler h) {
        routes.add(new Route("PUT", pattern, a, h));
    }

    public void delete(String pattern, Access a, Handler h) {
        routes.add(new Route("DELETE", pattern, a, h));
    }

    /** Returns the matching route, or null. {@code pathMatchedOtherMethod[0]} is set to true for 405 handling. */
    public Match find(String method, String path, boolean[] pathMatchedOtherMethod) {
        String[] segs = split(path);
        for (Route r : routes) {
            Map<String, String> params = match(r.segments, segs);
            if (params == null) {
                continue;
            }
            if (r.method.equals(method)) {
                return new Match(r, params);
            }
            pathMatchedOtherMethod[0] = true;
        }
        return null;
    }

    private static Map<String, String> match(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                params.put(p.substring(1, p.length() - 1), Ctx.decode(actual[i]));
            } else if (!p.equals(actual[i])) {
                return null;
            }
        }
        return params;
    }

    private static String[] split(String path) {
        String p = path;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? new String[0] : p.split("/");
    }
}
