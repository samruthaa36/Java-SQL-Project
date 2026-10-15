package com.smartqueue.service;

import com.smartqueue.db.Database;
import com.smartqueue.db.Row;
import com.smartqueue.service.LiveQueue.ServiceLive;
import com.smartqueue.util.Times;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Data that needs no sign-in: the lobby display board and the service list. */
public final class PublicService {

    private PublicService() {
    }

    public static List<Row> services() {
        List<Row> list = new ArrayList<>();
        for (ServiceLive l : LiveQueue.all().values()) {
            if (l.service.bool("isActive")) {
                list.add(LiveQueue.summary(l));
            }
        }
        return list;
    }

    private static String firstName(String full) {
        if (full == null) {
            return null;
        }
        String f = full.trim();
        int sp = f.indexOf(' ');
        return sp > 0 ? f.substring(0, sp) : f;
    }

    /** Everything the lobby TV shows: counters with the token being served, next tokens, crowd level. */
    public static Row board() {
        Map<Long, ServiceLive> all = LiveQueue.all();
        List<Row> counters = new ArrayList<>();
        List<Row> services = new ArrayList<>();
        for (ServiceLive l : all.values()) {
            Row sum = LiveQueue.summary(l);
            List<String> next = new ArrayList<>();
            for (int i = 0; i < Math.min(4, l.waiting.size()); i++) {
                next.add(l.waiting.get(i).str("tokenCode"));
            }
            sum.put("nextTokens", next);
            services.add(sum);
            for (Row c : l.counters) {
                counters.add(new Row()
                        .with("id", c.lng("id"))
                        .with("name", c.str("name"))
                        .with("status", c.str("status"))
                        .with("serviceName", l.service.str("name"))
                        .with("serviceColor", l.service.str("color"))
                        .with("staffName", firstName(c.str("staffName")))
                        .with("currentCode", c.str("currentCode"))
                        .with("currentStatus", c.str("currentStatus")));
            }
        }
        List<Row> recent = Database.queryTop("SELECT t.token_code, c.name AS counter_name, s.color AS service_color, t.last_called_at "
                + "FROM tokens t JOIN counters c ON c.id = t.counter_id JOIN services s ON s.id = t.service_id "
                + "WHERE t.token_date = ? AND t.last_called_at IS NOT NULL ORDER BY t.last_called_at DESC", 8, Times.today());
        Row out = new Row();
        out.put("orgName", Settings.get("org_name"));
        out.put("counters", counters);
        out.put("services", services);
        out.put("recentCalls", recent);
        out.put("crowd", LiveQueue.overallCrowd(all));
        out.put("serverTime", Times.now());
        return out;
    }
}
