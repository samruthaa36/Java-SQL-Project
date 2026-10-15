package com.smartqueue.api;

import com.smartqueue.http.Router;
import com.smartqueue.http.Router.Access;
import com.smartqueue.service.PublicService;
import com.smartqueue.service.Settings;

import java.util.LinkedHashMap;
import java.util.Map;

/** Endpoints that need no sign-in. */
public final class PublicApi {

    private PublicApi() {
    }

    public static void register(Router r) {
        r.get("/api/public/config", Access.PUBLIC, c -> Settings.publicInfo());

        r.get("/api/public/services", Access.PUBLIC, c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("services", PublicService.services());
            return out;
        });

        r.get("/api/public/board", Access.PUBLIC, c -> PublicService.board());
    }
}
