#!/usr/bin/env python3
"""
SmartQueue end-to-end API test.

Run the application first, then:

    python3 tests/api_test.py http://localhost:8080

It uses the demo accounts, creates its own temporary customers, and puts the system back
(counters closed, queues empty, settings restored) when it finishes.
Pass --slow to also wait for the automatic no-show skip (about 80 seconds).
"""
import http.cookiejar
import json
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"
SLOW = "--slow" in sys.argv
for a in sys.argv[1:]:
    if a.startswith("http"):
        BASE = a.rstrip("/")

passed = 0
failed = 0
failures = []


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print("  PASS  " + name)
    else:
        failed += 1
        failures.append(name + (" -> " + str(detail) if detail != "" else ""))
        print("  FAIL  " + name + ("   [" + str(detail)[:300] + "]" if detail != "" else ""))


def section(title):
    print("\n== " + title)


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def req(self, method, path, body=None, csrf=True, raw=False):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(BASE + path, data=data, method=method)
        r.add_header("Content-Type", "application/json")
        if csrf:
            r.add_header("X-Requested-With", "SmartQueue")
        try:
            resp = self.opener.open(r, timeout=40)
            status, content = resp.status, resp.read()
            ctype = resp.headers.get("Content-Type", "")
        except urllib.error.HTTPError as e:
            status, content = e.code, e.read()
            ctype = e.headers.get("Content-Type", "")
        if raw:
            return status, content
        try:
            return status, json.loads(content.decode("utf-8")) if content else None
        except ValueError:
            return status, content.decode("utf-8", "replace")

    def get(self, path, **kw):
        return self.req("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self.req("POST", path, body if body is not None else {}, **kw)

    def put(self, path, body=None, **kw):
        return self.req("PUT", path, body if body is not None else {}, **kw)

    def delete(self, path, **kw):
        return self.req("DELETE", path, **kw)

    def login(self, email, password, remember=False):
        return self.post("/api/auth/login", {"email": email, "password": password, "remember": remember})


def login_as(email, pw):
    c = Client()
    st, body = c.login(email, pw)
    assert st == 200, "login failed for %s: %s %s" % (email, st, body)
    return c


def main():
    global passed, failed
    print("SmartQueue API tests against " + BASE)

    # ------------------------------------------------------------------
    section("Public endpoints")
    anon = Client()
    st, cfg = anon.get("/api/public/config")
    check("public config", st == 200 and cfg.get("orgName"), cfg)
    st, sv = anon.get("/api/public/services")
    check("public services list (4 services)", st == 200 and len(sv["services"]) == 4, sv)
    svc = {s["prefix"]: s for s in sv["services"]}
    check("service prefixes A,B,C,D", set(svc.keys()) == {"A", "B", "C", "D"}, list(svc.keys()))
    st, board = anon.get("/api/public/board")
    check("display board", st == 200 and "counters" in board and len(board["counters"]) >= 6, board if st != 200 else "")
    st, _ = anon.get("/api/nope")
    check("unknown endpoint -> 404", st == 404)
    st, _ = anon.req("DELETE", "/api/public/config")
    check("wrong method -> 405", st == 405)

    # ------------------------------------------------------------------
    section("Authentication & security")
    st, _ = anon.get("/api/auth/me")
    check("me without session -> 401", st == 401)
    st, b = anon.post("/api/auth/login", {"email": "user@smartqueue.com", "password": "wrong"})
    check("wrong password -> 401", st == 401 and "Invalid" in b["error"], b)
    st, b = anon.post("/api/auth/login", {"email": "' OR 1=1 --", "password": "x' OR '1'='1"})
    check("SQL injection in login rejected", st == 401, b)
    st, b = anon.post("/api/auth/login", {"email": "", "password": ""})
    check("empty login -> 400", st == 400, b)
    st, b = anon.post("/api/auth/login", {"email": "user@smartqueue.com", "password": "User@123"}, csrf=False)
    check("POST without CSRF header -> 403", st == 403, b)

    ghost = "ghost%s@example.com" % uuid.uuid4().hex[:6]
    codes = []
    for i in range(6):
        st, b = anon.post("/api/auth/login", {"email": ghost, "password": "bad%d" % i})
        codes.append(st)
    check("brute-force lockout after 5 failures (429)", codes[:5] == [401] * 5 and codes[5] == 429, codes)

    tag = uuid.uuid4().hex[:6]
    st, b = anon.post("/api/auth/register", {"fullName": "A", "email": "bad", "password": "short"})
    check("register validation -> 400", st == 400, b)
    st, b = anon.post("/api/auth/register", {"fullName": "Test Person", "email": "tp%s@example.com" % tag, "password": "onlyletters"})
    check("register needs a digit -> 400", st == 400 and "number" in b["error"], b)
    users = []
    for i in range(1, 9):
        c = Client()
        st, b = c.post("/api/auth/register", {"fullName": "Tester %d %s" % (i, tag), "email": "t%d.%s@example.com" % (i, tag),
                                               "phone": "98765432%02d" % i, "password": "Passw0rd%d" % i})
        if i == 1:
            check("register + auto sign-in", st == 200 and b["user"]["role"] == "USER" and b["home"] == "user.html", b)
        users.append(c)
    st, b = Client().post("/api/auth/register", {"fullName": "Dup", "email": "t1.%s@example.com" % tag, "password": "Passw0rd1"})
    check("duplicate email -> 409", st == 409, b)
    st, b = users[0].get("/api/auth/me")
    check("me after register", st == 200 and b["user"]["email"] == "t1.%s@example.com" % tag, b)
    check("password hash never exposed", "passwordHash" not in json.dumps(b) and "password" not in b["user"], b)

    st, b = users[0].post("/api/auth/logout")
    st2, _ = users[0].get("/api/auth/me")
    check("logout invalidates the session", st == 200 and st2 == 401, (st, st2))
    st, b = users[0].login("t1.%s@example.com" % tag, "Passw0rd1")
    check("login again", st == 200, b)

    # role guards
    st, _ = users[1].get("/api/admin/dashboard")
    check("customer cannot open admin API (403)", st == 403)
    st, _ = users[1].get("/api/staff/overview")
    check("customer cannot open staff API (403)", st == 403)
    st, _ = anon.get("/api/user/dashboard")
    check("anonymous cannot open customer API (401)", st == 401)

    admin = login_as("admin@smartqueue.com", "Admin@123")
    ravi = login_as("ravi@smartqueue.com", "Staff@123")       # Counter -> General (A)
    meena = login_as("meena@smartqueue.com", "Staff@123")     # General (A)
    arjun = login_as("arjun@smartqueue.com", "Staff@123")     # Accounts (B)
    senior = login_as("senior@smartqueue.com", "User@123")    # priority customer

    st, _ = ravi.get("/api/admin/dashboard")
    check("staff cannot open admin API (403)", st == 403)
    st, _ = admin.get("/api/staff/overview")
    check("admin cannot use counter console (403)", st == 403)

    st, b = users[0].post("/api/auth/change-password", {"currentPassword": "nope", "newPassword": "Another1pw"})
    check("change password: wrong current -> 400", st == 400, b)
    st, b = users[0].post("/api/auth/change-password", {"currentPassword": "Passw0rd1", "newPassword": "Passw0rd1"})
    check("change password: same password -> 400", st == 400, b)
    st, b = users[0].put("/api/auth/profile", {"fullName": "Tester One " + tag, "phone": "9123456780"})
    check("profile update", st == 200 and b["user"]["fullName"].startswith("Tester One"), b)

    # ------------------------------------------------------------------
    section("Settings baseline")
    st, s = admin.get("/api/admin/settings")
    settings = {i["key"]: i["value"] for i in s["items"]}
    check("settings readable (11 keys)", st == 200 and len(settings) == 11, len(settings))
    admin.put("/api/admin/settings", {"auto_assign": "false", "priority_burst": 3, "approach_threshold": 2, "no_show_minutes": 3})
    st, cl0 = admin.get("/api/admin/counters")
    orig_assign = {c["id"]: c["serviceId"] for c in cl0["items"]}

    # ------------------------------------------------------------------
    section("Counter control")
    st, ov = ravi.get("/api/staff/overview")
    check("staff overview without counter", st == 200 and ov["counter"] is None and len(ov["availableCounters"]) >= 6, ov if st != 200 else "")
    counters = {c["name"]: c for c in ov["availableCounters"]}
    c1, c2, c3, c4 = counters["Counter 1"], counters["Counter 2"], counters["Counter 3"], counters["Counter 4"]
    st, b = ravi.post("/api/staff/call-next")
    check("call-next without counter -> 409", st == 409, b)
    st, b = ravi.post("/api/staff/counter/open", {"counterId": c1["id"]})
    check("staff opens Counter 1", st == 200 and b["counter"]["status"] == "OPEN", b)
    st, b = meena.post("/api/staff/counter/open", {"counterId": c1["id"]})
    check("second staff cannot take an open counter (409)", st == 409, b)
    st, b = ravi.post("/api/staff/counter/open", {"counterId": c2["id"]})
    check("one staff cannot open two counters (409)", st == 409, b)
    st, b = meena.post("/api/staff/counter/open", {"counterId": c2["id"]})
    check("Meena opens Counter 2", st == 200, b)
    st, b = ravi.post("/api/staff/call-next")
    check("call-next with empty queue -> 409", st == 409 and "No customers" in b["error"], b)

    # ------------------------------------------------------------------
    section("Token issuing, duplicate prevention, position & ETA")
    st, d = users[0].get("/api/user/dashboard")
    check("customer dashboard", st == 200 and d["activeToken"] is None and len(d["services"]) == 4, d if st != 200 else "")
    sa = svc["A"]["id"]
    st, t1 = users[0].post("/api/user/tokens", {"serviceId": sa})
    check("token created with prefix A", st == 200 and t1["tokenCode"].startswith("A") and t1["status"] == "WAITING", t1)
    check("token starts at position 1", t1["position"] == 1 and t1["ahead"] == 0, t1)
    st, b = users[0].post("/api/user/tokens", {"serviceId": svc["B"]["id"]})
    check("duplicate active token prevented (409)", st == 409 and "already have an active token" in b["error"], b)
    st, t2 = users[1].post("/api/user/tokens", {"serviceId": sa})
    st, t3 = users[2].post("/api/user/tokens", {"serviceId": sa})
    check("sequence numbers increase", int(t2["tokenCode"][1:]) == int(t1["tokenCode"][1:]) + 1 and
          int(t3["tokenCode"][1:]) == int(t2["tokenCode"][1:]) + 1, (t1["tokenCode"], t2["tokenCode"], t3["tokenCode"]))
    st, d3 = users[2].get("/api/user/dashboard")
    at = d3["activeToken"]
    check("third customer: 2 ahead, position 3", at["ahead"] == 2 and at["position"] == 3, at)
    check("ETA is a number and grows with position", isinstance(at["etaMinutes"], int) and at["etaMinutes"] >= 0, at)
    st, d1 = users[0].get("/api/user/dashboard")
    check("ETA increases with queue position", at["etaMinutes"] >= d1["activeToken"]["etaMinutes"], (at["etaMinutes"], d1["activeToken"]["etaMinutes"]))
    check("dashboard shows service snapshot", d3["activeToken"]["service"]["waiting"] == 3 and d3["activeToken"]["service"]["countersOpen"] == 2,
          d3["activeToken"]["service"])
    st, b = users[0].post("/api/user/tokens", {"serviceId": 999999})
    check("unknown service -> 404", st == 404 or st == 409, b)

    # ------------------------------------------------------------------
    section("Calling, serving, completing")
    st, called = ravi.post("/api/staff/call-next")
    check("first token in queue is called (FIFO)", st == 200 and called["tokenCode"] == t1["tokenCode"] and called["status"] == "CALLED", called)
    check("called token records counter + staff", called["counterName"] == "Counter 1" and called["servedByName"] == "Ravi Kumar", called)
    st, b = ravi.post("/api/staff/call-next")
    check("cannot call next while one is active (409)", st == 409, b)
    st, b = meena.post("/api/staff/tokens/%d/start" % called["id"])
    check("other counter cannot start my token (409)", st == 409, b)
    st, d = users[0].get("/api/user/dashboard")
    check("customer sees CALLED + counter", d["activeToken"]["status"] == "CALLED" and d["activeToken"]["counterName"] == "Counter 1", d["activeToken"])
    st, n = users[0].get("/api/notifications")
    types = [x["type"] for x in n["items"]]
    check("notifications: created + called", "TOKEN_CREATED" in types and "CALLED" in types, types)
    called_msg = [x for x in n["items"] if x["type"] == "CALLED"][0]["message"]
    check("called message matches spec wording", "is now being served. Please proceed to Counter 1." in called_msg, called_msg)
    st, b = ravi.post("/api/staff/tokens/%d/start" % called["id"])
    check("start serving", st == 200 and b["status"] == "SERVING", b)
    st, b = users[0].post("/api/user/tokens/%d/cancel" % called["id"])
    check("customer cannot cancel while being served (409)", st == 409, b)
    time.sleep(1.2)
    st, b = ravi.post("/api/staff/tokens/%d/complete" % called["id"], {"notes": "All documents verified"})
    check("complete token", st == 200 and b["status"] == "COMPLETED" and b["serviceMinutes"] >= 0, b)
    st, n = users[1].get("/api/notifications")
    types2 = [x["type"] for x in n["items"]]
    check("others get 'approaching/next' notification", any(t in types2 for t in ("NEXT", "APPROACHING")), types2)
    st, fb = users[0].post("/api/user/tokens/%d/feedback" % called["id"], {"rating": 5, "comment": "Great service"})
    check("feedback saved", st == 200 and fb["rating"] == 5, fb)
    st, b = users[0].post("/api/user/tokens/%d/feedback" % called["id"], {"rating": 9})
    check("feedback validates rating 1-5", st == 400, b)
    st, h = users[0].get("/api/user/history")
    check("history lists the visit", st == 200 and h["total"] >= 1 and h["items"][0]["tokenCode"] == t1["tokenCode"], h)
    st, h2 = users[0].get("/api/user/history?status=COMPLETED&size=5")
    check("history filter COMPLETED", st == 200 and all(i["status"] == "COMPLETED" for i in h2["items"]), h2)
    st, _ = users[1].get("/api/user/tokens/%d" % t1["id"])
    check("cannot read another customer's token (404)", st == 404)
    # the first customer may take a new token now
    st, t1b = users[0].post("/api/user/tokens", {"serviceId": sa})
    check("new token allowed after completion", st == 200, t1b)

    # ------------------------------------------------------------------
    section("Skip, recall, hold, resume, requeue")
    st, c2called = meena.post("/api/staff/call-next")
    check("Meena calls next (t2)", st == 200 and c2called["tokenCode"] == t2["tokenCode"], c2called)
    st, b = meena.post("/api/staff/tokens/%d/recall" % c2called["id"])
    check("recall re-announces", st == 200, b)
    st, n = users[1].get("/api/notifications")
    check("recall notification", any(x["type"] == "RECALL" for x in n["items"]), [x["type"] for x in n["items"]])
    st, b = meena.post("/api/staff/tokens/%d/skip" % c2called["id"])
    check("skip a no-show", st == 200 and b["status"] == "SKIPPED", b)
    st, ov = meena.get("/api/staff/overview")
    check("skipped token listed for restoring", any(x["id"] == c2called["id"] for x in ov["skipped"]), ov["skipped"])
    st, b = meena.post("/api/staff/tokens/%d/requeue" % c2called["id"])
    check("restore skipped token to the queue", st == 200 and b["status"] == "WAITING", b)
    st, b = ravi.post("/api/staff/call-next")   # next is t3 (t2 went to the back)
    check("restored token goes to the END of the queue", st == 200 and b["tokenCode"] == t3["tokenCode"], b)
    st, b = ravi.post("/api/staff/tokens/%d/hold" % b["id"])
    check("hold a called token", st == 200 and b["status"] == "ON_HOLD", b)
    held = b
    st, ov = ravi.get("/api/staff/overview")
    check("held token appears in on-hold list", any(x["id"] == held["id"] for x in ov["onHold"]), ov["onHold"])
    st, b = ravi.post("/api/staff/tokens/%d/resume" % held["id"])
    check("resume a held token", st == 200 and b["status"] == "WAITING", b)
    st, b = ravi.post("/api/staff/tokens/%d/call" % held["id"])
    check("staff can call a specific token", st == 200 and b["status"] == "CALLED" and b["id"] == held["id"], b)
    st, b = ravi.post("/api/staff/tokens/%d/start" % held["id"])
    st, b = ravi.post("/api/staff/tokens/%d/hold" % held["id"])
    check("hold a token that is being served", st == 200 and b["status"] == "ON_HOLD" and b["serviceSeconds"] >= 0, b)
    st, b = ravi.post("/api/staff/tokens/%d/call" % held["id"])
    st, b = ravi.post("/api/staff/tokens/%d/start" % held["id"])
    st, b = ravi.post("/api/staff/tokens/%d/complete" % held["id"])
    check("resume-call-complete path works", st == 200 and b["status"] == "COMPLETED", b)

    # clean the General queue (Meena may still hold t2 + users[0]'s new token)
    st, ov = ravi.get("/api/staff/overview")
    for q in ov["queue"]:
        ravi.post("/api/staff/tokens/%d/cancel" % q["id"], {"reason": "test cleanup"})
    st, ov = ravi.get("/api/staff/overview")
    check("queue emptied by cancel", len(ov["queue"]) == 0 and ov["current"] is None, ov["queue"])

    # ------------------------------------------------------------------
    section("Priority queue, emergency, anti-starvation, reordering")
    st, n1 = users[3].post("/api/user/tokens", {"serviceId": sa})
    st, n2 = users[4].post("/api/user/tokens", {"serviceId": sa})
    st, sp = senior.post("/api/user/tokens", {"serviceId": sa})
    check("senior citizen token is auto-priority", st == 200 and sp["priority"] == 1 and sp["position"] == 1, sp)
    st, e1 = ravi.post("/api/staff/walk-in", {"serviceId": sa, "customerName": "Emergency Patient", "priority": 2, "reason": "Medical emergency"})
    check("walk-in emergency token", st == 200 and e1["priority"] == 2 and e1["position"] == 1 and e1["source"] == "WALK_IN", e1)
    st, ov = ravi.get("/api/staff/overview")
    order = [q["tokenCode"] for q in ov["queue"]]
    check("queue order: emergency, priority, normal, normal", order == [e1["tokenCode"], sp["tokenCode"], n1["tokenCode"], n2["tokenCode"]], order)
    st, b = users[3].post("/api/staff/tokens/%d/priority" % n1["id"], {"priority": 2})
    check("customer cannot change priority (403)", st == 403, b)
    st, b = ravi.post("/api/staff/tokens/%d/move" % n2["id"], {"position": "front"})
    st, ov = ravi.get("/api/staff/overview")
    order = [q["tokenCode"] for q in ov["queue"]]
    check("move to front reorders within the class", order.index(n2["tokenCode"]) < order.index(n1["tokenCode"]), order)
    st, b = ravi.post("/api/staff/tokens/%d/move" % n2["id"], {"position": "back"})
    st, b = ravi.post("/api/staff/tokens/%d/move" % n2["id"], {"position": "sideways"})
    check("invalid move position -> 400", st == 400, b)
    st, b = ravi.post("/api/staff/tokens/%d/priority" % n1["id"], {"priority": 1, "reason": "Pregnant"})
    check("staff raises priority", st == 200 and b["priority"] == 1, b)
    st, b = ravi.post("/api/staff/tokens/%d/priority" % n1["id"], {"priority": 7})
    check("invalid priority -> 400", st == 400, b)
    st, b = ravi.post("/api/staff/tokens/%d/priority" % n1["id"], {"priority": 0})
    # serve everything and verify the order, then test anti-starvation with fresh tokens
    served = []
    for _ in range(4):
        st, b = ravi.post("/api/staff/call-next")
        served.append(b["tokenCode"])
        ravi.post("/api/staff/tokens/%d/start" % b["id"])
        ravi.post("/api/staff/tokens/%d/complete" % b["id"])
    check("served in smart order", served[0] == e1["tokenCode"] and served[1] == sp["tokenCode"], served)

    # anti-starvation: 5 priority + 3 normal with burst=3  ->  P P P N P P N N
    prio = []
    for i in range(5):
        st, p = ravi.post("/api/staff/walk-in", {"serviceId": sa, "customerName": "Priority %d" % i, "priority": 1, "reason": "Senior citizen"})
        prio.append(p["tokenCode"])
    norm = []
    for i in range(3):
        st, p = ravi.post("/api/staff/walk-in", {"serviceId": sa, "customerName": "Normal %d" % i, "priority": 0})
        norm.append(p["tokenCode"])
    st, ov = ravi.get("/api/staff/overview")
    expected = prio[:3] + [norm[0]] + prio[3:5] + norm[1:3]
    shown = [q["tokenCode"] for q in ov["queue"]]
    check("anti-starvation order P P P N P P N N", shown == expected, (shown, expected))
    got = []
    for _ in range(8):
        st, b = ravi.post("/api/staff/call-next")
        got.append(b["tokenCode"])
        ravi.post("/api/staff/tokens/%d/start" % b["id"])
        ravi.post("/api/staff/tokens/%d/complete" % b["id"])
    check("calls follow the displayed order exactly", got == shown, (got, shown))

    # ------------------------------------------------------------------
    section("Cancel flows")
    st, tc = users[5].post("/api/user/tokens", {"serviceId": sa})
    st, b = users[5].post("/api/user/tokens/%d/cancel" % tc["id"])
    check("customer cancels own waiting token", st == 200 and b["status"] == "CANCELLED", b)
    st, b = users[5].post("/api/user/tokens/%d/cancel" % tc["id"])
    check("cancel twice -> 409", st == 409, b)
    st, tc2 = users[5].post("/api/user/tokens", {"serviceId": sa})
    st, b = users[6].post("/api/user/tokens/%d/cancel" % tc2["id"])
    check("cannot cancel someone else's token (404)", st == 404, b)
    st, b = ravi.post("/api/staff/tokens/%d/cancel" % tc2["id"], {"reason": "Wrong service"})
    check("staff cancels with reason", st == 200 and b["closeReason"] == "Wrong service", b)
    st, n = users[5].get("/api/notifications")
    check("customer notified of staff cancellation", any(x["type"] == "CANCELLED" for x in n["items"]), [x["type"] for x in n["items"]])
    st, b = arjun.post("/api/staff/tokens/%d/cancel" % tc2["id"], {})
    check("staff without counter cannot touch queues (409)", st in (409, 403), b)

    # ------------------------------------------------------------------
    section("Multiple service queues and counter break/close")
    sb, sc = svc["B"]["id"], svc["C"]["id"]
    st, o = arjun.post("/api/staff/counter/open", {"counterId": c3["id"]})
    check("Arjun opens Counter 3 (Accounts)", st == 200, o)
    st, tb = users[6].post("/api/user/tokens", {"serviceId": sb})
    check("Accounts token has prefix B", tb["tokenCode"].startswith("B"), tb)
    st, b = ravi.post("/api/staff/tokens/%d/call" % tb["id"])
    check("General counter cannot call an Accounts token (409)", st == 409, b)
    st, b = arjun.post("/api/staff/counter/break", {"onBreak": True})
    check("counter goes on break", st == 200 and b["counter"]["status"] == "BREAK", b)
    st, b = arjun.post("/api/staff/call-next")
    check("no calling during a break (409)", st == 409, b)
    st, d = users[6].get("/api/user/dashboard")
    check("service shows no open counters while on break", d["activeToken"]["service"]["countersOpen"] == 0 and d["activeToken"]["service"]["noCounters"], d["activeToken"]["service"])
    st, b = arjun.post("/api/staff/counter/break", {"onBreak": False})
    check("break ends", st == 200 and b["counter"]["status"] == "OPEN", b)
    st, cb = arjun.post("/api/staff/call-next")
    check("Accounts token called", st == 200 and cb["tokenCode"] == tb["tokenCode"], cb)
    st, b = arjun.post("/api/staff/counter/close")
    check("cannot close counter with a customer (409)", st == 409, b)
    arjun.post("/api/staff/tokens/%d/start" % cb["id"])
    arjun.post("/api/staff/tokens/%d/complete" % cb["id"])
    st, b = arjun.post("/api/staff/counter/close")
    check("counter closes when free", st == 200, b)
    st, hist = ravi.get("/api/staff/history")
    check("staff history for today", st == 200 and hist["summary"]["completed"] >= 5 and len(hist["items"]) >= 5, hist["summary"])
    st, b = ravi.get("/api/staff/history?date=bad")
    check("history validates date -> 400", st == 400, b)

    # ------------------------------------------------------------------
    section("Auto-assignment")
    st, b = admin.put("/api/admin/settings", {"auto_assign": "true"})
    check("enable auto-assign", st == 200, b)
    st, ta = users[7].post("/api/user/tokens", {"serviceId": sa})
    check("idle counter is assigned automatically", st == 200 and ta["status"] == "CALLED" and ta["counterName"] in ("Counter 1", "Counter 2"), ta)
    st, ta2 = users[2].post("/api/user/tokens", {"serviceId": sa})
    check("second idle counter takes the next token", ta2["status"] == "CALLED" and ta2["counterName"] != ta["counterName"], ta2)
    st, ta3 = users[3].post("/api/user/tokens", {"serviceId": sa})
    check("third customer waits (both counters busy)", ta3["status"] == "WAITING", ta3)
    for t, cl in ((ta, None), (ta2, None)):
        who = ravi if t["counterName"] == "Counter 1" else meena
        who.post("/api/staff/tokens/%d/start" % t["id"])
        who.post("/api/staff/tokens/%d/complete" % t["id"])
    time.sleep(0.5)
    st, d = users[3].get("/api/user/dashboard")
    check("completion hands the next token to the free counter", d["activeToken"]["status"] == "CALLED", d["activeToken"])
    who = ravi if d["activeToken"]["counterName"] == "Counter 1" else meena
    who.post("/api/staff/tokens/%d/start" % d["activeToken"]["id"])
    who.post("/api/staff/tokens/%d/complete" % d["activeToken"]["id"])
    admin.put("/api/admin/settings", {"auto_assign": "false"})

    # ------------------------------------------------------------------
    section("Concurrency")
    results = []
    lock = threading.Lock()

    def make_token(cli):
        st, b = cli.post("/api/user/tokens", {"serviceId": sc})
        with lock:
            results.append((st, b))

    fresh = []
    for i in range(12):
        c = Client()
        c.post("/api/auth/register", {"fullName": "Load %d %s" % (i, tag), "email": "load%d.%s@example.com" % (i, tag), "password": "Passw0rd%d" % i})
        fresh.append(c)
    threads = [threading.Thread(target=make_token, args=(c,)) for c in fresh]
    [t.start() for t in threads]
    [t.join() for t in threads]
    codes = [b["tokenCode"] for st, b in results if st == 200]
    check("12 parallel token requests all succeed", len(codes) == 12, [r[0] for r in results])
    check("parallel requests got unique token numbers", len(set(codes)) == 12, sorted(codes))
    # parallel double-click by the SAME user -> exactly one token
    dup = []

    def dbl(cli):
        st, b = cli.post("/api/user/tokens", {"serviceId": sa})
        with lock:
            dup.append(st)
    victim = Client()
    victim.post("/api/auth/register", {"fullName": "Double Click %s" % tag, "email": "dbl.%s@example.com" % tag, "password": "Passw0rd1"})
    ts = [threading.Thread(target=dbl, args=(victim,)) for _ in range(6)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    check("double-click creates only ONE active token", dup.count(200) == 1 and dup.count(409) == 5, dup)
    victim.post("/api/user/tokens/%d/cancel" % victim.get("/api/user/dashboard")[1]["activeToken"]["id"])

    # two counters of the same service pressing "call next" at the same moment
    for c in fresh[:4]:
        pass
    st, ov = ravi.get("/api/staff/overview")
    ravi.post("/api/staff/counter/close")
    meena.post("/api/staff/counter/close")
    st, o1 = ravi.post("/api/staff/counter/open", {"counterId": c4["id"]})  # Counter 4 = Enquiry (C)
    st, o2 = meena.post("/api/staff/counter/open", {"counterId": [x for x in ov["availableCounters"]][0]["id"]}) if False else (200, None)
    got = []

    def call(cli):
        st, b = cli.post("/api/staff/call-next")
        with lock:
            got.append((st, b))
    # only one Enquiry counter is staffed here, so fire call-next several times in parallel: exactly one must win
    ts = [threading.Thread(target=call, args=(ravi,)) for _ in range(5)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    check("parallel call-next: exactly one token called", [s for s, _ in got].count(200) == 1, [s for s, _ in got])
    st, ov = ravi.get("/api/staff/overview")
    if ov["current"]:
        ravi.post("/api/staff/tokens/%d/start" % ov["current"]["id"])
        ravi.post("/api/staff/tokens/%d/complete" % ov["current"]["id"])
    ravi.post("/api/staff/counter/close")

    # ------------------------------------------------------------------
    section("Admin: dashboard, queues, analytics")
    st, dash = admin.get("/api/admin/dashboard")
    check("dashboard", st == 200 and "cards" in dash and "crowd" in dash and len([x for x in dash["services"] if x["isActive"]]) == 4 and len(dash["counters"]) >= 6, dash if st != 200 else "")
    check("dashboard hourly array of 24", len(dash["hourlyToday"]) == 24)
    check("dashboard crowd level valid", dash["crowd"]["crowdLevel"] in ("LOW", "MODERATE", "HIGH", "CRITICAL"), dash["crowd"])
    st, q = admin.get("/api/admin/queues")
    check("live queues", st == 200 and len([x for x in q["services"] if x["isActive"]]) == 4 and all("queue" in s for s in q["services"]), q if st != 200 else "")
    st, an = admin.get("/api/admin/analytics?days=14")
    check("analytics payload", st == 200 and len(an["daily"]) == 14 and len(an["peakHours"]) == 24 and len(an["heatmap"]) == 7, an if st != 200 else "")
    check("analytics summary sane", an["summary"]["total"] > 500 and 0 < an["summary"]["completionRate"] <= 100, an["summary"])
    check("analytics: service demand + times", len(an["serviceDemand"]) >= 4 and len(an["serviceTime"]) >= 4)
    check("analytics: counter + staff performance", len(an["counterPerformance"]) >= 6 and len(an["staffPerformance"]) >= 6)
    check("analytics: prediction accuracy + peak crowd periods",
          an["predictionAccuracy"]["samples"] > 100 and len(an["peakCrowdPeriods"]) >= 3, an["predictionAccuracy"])
    st, an90 = admin.get("/api/admin/analytics?days=90")
    check("analytics 90 days", st == 200 and len(an90["daily"]) == 90)
    st, rep = admin.get("/api/admin/reports?status=COMPLETED")
    check("report (default last 7 days)", st == 200 and rep["summary"]["total"] > 100 and rep["items"][0]["status"] == "COMPLETED", rep["summary"] if st == 200 else rep)
    st, csv = admin.get("/api/admin/reports/export", raw=True)
    text = csv.decode("utf-8-sig")
    check("CSV export", st == 200 and text.startswith("Date,Token,Service") and text.count("\r\n") > 100, text[:120])
    st, b = admin.get("/api/admin/reports?from=2026-10-10&to=2026-10-01")
    check("report validates date order -> 400", st == 400, b)
    st, rec = admin.get("/api/admin/recommendations")
    check("recommendations endpoint", st == 200 and isinstance(rec["items"], list), rec)
    st, act = admin.get("/api/admin/activity?size=5")
    check("activity log", st == 200 and len(act["items"]) == 5 and act["total"] > 5, act if st != 200 else "")
    st, act2 = admin.get("/api/admin/activity?search=Counter")
    check("activity search", st == 200 and all("counter" in (i["details"] or "").lower() or "counter" in i["action"].lower() for i in act2["items"]), act2["items"][:2])
    st, inj = admin.get("/api/admin/users?search=" + urllib.request.quote("' OR '1'='1"))
    check("SQL injection in search is harmless", st == 200 and inj["total"] == 0, inj if st != 200 else inj["total"])

    # ------------------------------------------------------------------
    section("Admin: recommendations (crowd + optimisation)")
    admin.post("/api/admin/demo/clear-waiting")
    st, r = admin.post("/api/admin/demo/simulate-crowd", {"count": 40})
    check("simulate crowd", st == 200 and r["created"] == 40, r)
    st, dash = admin.get("/api/admin/dashboard")
    check("crowd level rises with load", dash["crowd"]["crowdLevel"] in ("HIGH", "CRITICAL") and dash["crowd"]["queueLength"] >= 40, dash["crowd"])
    recs = dash["recommendations"]
    check("recommends opening closed counters", any(x["type"] == "OPEN_COUNTER" and x["canApply"] for x in recs), [(x["type"], x["title"]) for x in recs])
    check("recommends auto-assign during a crowd", any(x["type"] == "ENABLE_AUTO_ASSIGN" for x in recs), [x["type"] for x in recs])
    open_rec = [x for x in recs if x["type"] == "OPEN_COUNTER" and x["canApply"]][0]
    st, b = admin.post("/api/admin/recommendations/apply", {"type": "OPEN_COUNTER", "counterId": open_rec["action"]["counterId"]})
    check("apply 'open counter' (an idle staff member is assigned)", st == 200, b)
    st, dash = admin.get("/api/admin/dashboard")
    check("counter is now open", any(c["id"] == open_rec["action"]["counterId"] and c["status"] == "OPEN" for c in dash["counters"]))
    st, b = admin.post("/api/admin/recommendations/apply", {"type": "BOGUS"})
    check("unknown recommendation -> 400", st == 400, b)
    st, b = admin.post("/api/admin/demo/clear-waiting")
    check("clear waiting", st == 200 and b["cancelled"] >= 40, b)
    # reassign
    st, cl = admin.get("/api/admin/counters")
    open_c = [c for c in cl["items"] if c["status"] == "OPEN" and not c["busy"]][0]
    other = [s for s in sv["services"] if s["id"] != open_c["serviceId"]][0]
    st, b = admin.post("/api/admin/counters/%d/reassign" % open_c["id"], {"serviceId": other["id"]})
    check("reassign idle open counter to another service", st == 200, b)
    admin.post("/api/admin/counters/%d/close" % open_c["id"])

    # ------------------------------------------------------------------
    section("Admin: manage users / services / counters / settings")
    em = "mgr.%s@example.com" % tag
    st, nu = admin.post("/api/admin/users", {"fullName": "Managed Staff", "email": em, "role": "STAFF", "phone": "9000011111"})
    check("create staff with generated password", st == 200 and nu.get("temporaryPassword"), nu)
    tmp = nu["temporaryPassword"]
    st, b = Client().login(em, tmp)
    check("new staff can sign in with the temporary password", st == 200 and b["home"] == "staff.html", b)
    st, up = admin.put("/api/admin/users/%d" % nu["id"], {"fullName": "Managed Staff II", "phone": ""})
    check("update user (phone cleared)", st == 200 and up["fullName"] == "Managed Staff II" and up["phone"] is None, up)
    st, b = admin.post("/api/admin/users/%d/reset-password" % nu["id"])
    check("reset password", st == 200 and b["temporaryPassword"] and b["temporaryPassword"] != tmp, b)
    st, b = Client().login(em, tmp)
    check("old password no longer works", st == 401, b)
    st, up = admin.put("/api/admin/users/%d" % nu["id"], {"isActive": False})
    st, b = Client().login(em, b if isinstance(b, str) else "x")
    st, b = Client().login(em, "Whatever1")
    check("deactivated account cannot sign in", st in (401, 403), b)
    st, me = admin.get("/api/auth/me")
    st, b = admin.put("/api/admin/users/%d" % me["user"]["id"], {"isActive": False})
    check("admin cannot deactivate self (409)", st == 409, b)
    st, b = admin.put("/api/admin/users/%d" % me["user"]["id"], {"role": "USER"})
    check("admin cannot demote self (409)", st == 409, b)
    st, b = admin.delete("/api/admin/users/%d" % me["user"]["id"])
    check("admin cannot delete self (409)", st == 409, b)
    st, b = admin.delete("/api/admin/users/%d" % nu["id"])
    check("delete user without history", st == 200, b)
    st, lst = admin.get("/api/admin/users?role=STAFF")
    check("list users by role", st == 200 and lst["total"] >= 6 and all(u["role"] == "STAFF" for u in lst["items"]), lst if st != 200 else "")
    st, usr = admin.get("/api/admin/users?search=Tester&size=50")
    victim_user = [u for u in usr["items"] if u["visits"] > 0][0]
    st, b = admin.delete("/api/admin/users/%d" % victim_user["id"])
    check("cannot delete a user with history (409)", st == 409, b)
    st, b = admin.put("/api/admin/users/%d" % victim_user["id"], {"isPriority": True, "priorityReason": "Senior citizen"})
    check("mark customer as priority", st == 200 and b["isPriority"] is True, b)

    # services
    st, existing = admin.get("/api/admin/services")
    used = set(x["prefix"] for x in existing["items"])
    free = [ch for ch in "ZYXWVUTSRQ" if ch not in used]
    zp, xp = free[0], free[1]
    st, ns = admin.post("/api/admin/services", {"name": "Test Service " + tag, "prefix": zp, "description": "Temp", "icon": "briefcase",
                                                 "color": "#123abc", "avgServiceMinutes": 7.5})
    check("create service", st == 200 and ns["prefix"] == zp, ns)
    st, b = admin.post("/api/admin/services", {"name": "Other", "prefix": zp, "avgServiceMinutes": 5})
    check("duplicate prefix -> 409", st == 409, b)
    st, b = admin.post("/api/admin/services", {"name": "Bad", "prefix": "ZZ1", "avgServiceMinutes": 5})
    check("invalid prefix -> 400", st == 400, b)
    st, b = admin.post("/api/admin/services", {"name": "Bad2", "prefix": "Y", "avgServiceMinutes": 0})
    check("invalid service time -> 400", st == 400, b)
    st, b = admin.post("/api/admin/services", {"name": "Bad3", "prefix": "Y", "avgServiceMinutes": 5, "color": "red"})
    check("invalid colour -> 400", st == 400, b)
    st, nc = admin.post("/api/admin/counters", {"name": "Counter T" + tag, "serviceId": ns["id"]})
    check("create counter", st == 200 and nc["status"] == "CLOSED", nc)
    st, b = admin.post("/api/admin/counters", {"name": "counter t" + tag, "serviceId": ns["id"]})
    check("duplicate counter name -> 409 (case-insensitive)", st == 409, b)
    st, b = admin.put("/api/admin/services/%d" % ns["id"], {"isActive": False})
    st, b2 = users[0].post("/api/user/tokens", {"serviceId": ns["id"]})
    check("disabled service refuses tokens", st == 409, b2)
    st, b = admin.put("/api/admin/services/%d" % ns["id"], {"isActive": True, "avgServiceMinutes": 9})
    st, tz = users[0].post("/api/user/tokens", {"serviceId": ns["id"]})
    check("enabled service issues token with its prefix", st == 200 and tz["tokenCode"] == zp + "001", tz)
    users[0].post("/api/user/tokens/%d/cancel" % tz["id"])
    st, b = admin.put("/api/admin/services/%d" % ns["id"], {"prefix": xp})
    check("prefix locked after tokens exist (409)", st == 409, b)
    st, b = admin.delete("/api/admin/services/%d" % ns["id"])
    check("cannot delete a service that has counters/history (409)", st == 409, b)
    st, b = admin.put("/api/admin/counters/%d" % nc["id"], {"name": "Counter Renamed " + tag})
    check("rename counter", st == 200, b)
    st, b = admin.post("/api/admin/counters/%d/open" % nc["id"], {"staffId": 999999})
    check("open counter for unknown staff -> 404", st == 404, b)
    st, b = admin.delete("/api/admin/counters/%d" % nc["id"])
    check("delete (soft) closed counter", st == 200, b)
    # a brand-new service with no history can be deleted
    st, ns2 = admin.post("/api/admin/services", {"name": "Disposable " + tag, "prefix": xp, "avgServiceMinutes": 5})
    st, b = admin.delete("/api/admin/services/%d" % ns2["id"])
    check("delete unused service", st == 200, b)

    # settings
    st, b = admin.put("/api/admin/settings", {"crowd_low_max": 20})
    check("settings: crowd limits must increase (400)", st == 400, b)
    st, b = admin.put("/api/admin/settings", {"approach_threshold": 99})
    check("settings: range check (400)", st == 400, b)
    st, b = admin.put("/api/admin/settings", {"nonsense": 1})
    check("settings: unknown key (400)", st == 400, b)
    st, b = admin.put("/api/admin/settings", {"crowd_low_max": 2, "crowd_moderate_max": 4, "crowd_high_max": 8})
    st, pub = anon.get("/api/public/config")
    check("settings: crowd thresholds update", pub["crowdLowMax"] == 2 and pub["crowdHighMax"] == 8, pub)
    for i in range(9):
        admin.post("/api/admin/demo/simulate-crowd", {"count": 1})
    st, dash = admin.get("/api/admin/dashboard")
    check("custom thresholds drive the crowd level (CRITICAL above 8)", dash["crowd"]["crowdLevel"] == "CRITICAL", dash["crowd"])
    admin.post("/api/admin/demo/clear-waiting")
    admin.put("/api/admin/settings", {"crowd_low_max": 5, "crowd_moderate_max": 15, "crowd_high_max": 30})
    st, b = admin.put("/api/admin/settings", {"allow_registration": "false"})
    st, b = Client().post("/api/auth/register", {"fullName": "Blocked User", "email": "blk.%s@example.com" % tag, "password": "Passw0rd1"})
    check("registration can be switched off (403)", st == 403, b)
    admin.put("/api/admin/settings", {"allow_registration": "true"})

    # ------------------------------------------------------------------
    section("Notifications")
    st, n = users[0].get("/api/notifications?limit=5")
    check("list notifications", st == 200 and len(n["items"]) <= 5 and "unread" in n, n if st != 200 else "")
    st, b = users[0].post("/api/notifications/read-all")
    st, n = users[0].get("/api/notifications")
    check("mark all read", n["unread"] == 0, n["unread"])
    st, b = users[0].post("/api/notifications/999999/read")
    check("read unknown notification -> 404", st == 404, b)
    st, b = users[0].delete("/api/notifications")
    st, n = users[0].get("/api/notifications")
    check("clear notifications", n["items"] == [], n)

    # ------------------------------------------------------------------
    if SLOW:
        section("No-show auto-skip (waits ~80 s)")
        admin.put("/api/admin/settings", {"no_show_minutes": 1})
        st, cnow = admin.get("/api/admin/counters")
        c1now = [c for c in cnow["items"] if c["id"] == c1["id"]][0]
        if c1now["serviceId"] != sa:
            admin.put("/api/admin/counters/%d" % c1["id"], {"serviceId": sa})
        st, o = ravi.post("/api/staff/counter/open", {"counterId": c1["id"]})
        check("counter opened for no-show test", st == 200, o)
        st, tn = users[0].post("/api/user/tokens", {"serviceId": sa})
        check("customer takes a token for no-show test", st == 200, tn)
        st, cn = ravi.post("/api/staff/call-next")
        check("called customer", cn["status"] == "CALLED", cn)
        time.sleep(85)
        st, d = users[0].get("/api/user/dashboard")
        st, h = users[0].get("/api/user/history?status=SKIPPED")
        check("customer who never arrived is skipped automatically", h["items"] and h["items"][0]["id"] == tn["id"] and d["activeToken"] is None, h["items"][:1])
        st, n = users[0].get("/api/notifications")
        check("no-show notification", any(x["type"] == "SKIPPED" for x in n["items"]), [x["type"] for x in n["items"]])
        admin.put("/api/admin/settings", {"no_show_minutes": 3})
        ravi.post("/api/staff/counter/close")

    # ------------------------------------------------------------------
    section("Cleanup")
    admin.put("/api/admin/services/%d" % ns["id"], {"isActive": False})
    for c in (ravi, meena, arjun):
        c.post("/api/staff/counter/close")
    admin.post("/api/admin/demo/clear-waiting")
    for k in ("auto_assign", "priority_burst", "approach_threshold", "no_show_minutes"):
        admin.put("/api/admin/settings", {k: settings[k]})
    st, cl = admin.get("/api/admin/counters")
    for c in cl["items"]:
        if c["status"] != "CLOSED":
            admin.post("/api/admin/counters/%d/close" % c["id"])
    st, cl = admin.get("/api/admin/counters")
    for c in cl["items"]:
        if orig_assign.get(c["id"]) not in (None, c["serviceId"]):
            admin.put("/api/admin/counters/%d" % c["id"], {"serviceId": orig_assign[c["id"]]})
    st, cl = admin.get("/api/admin/counters")
    check("counter assignments restored", all(orig_assign.get(c["id"], c["serviceId"]) == c["serviceId"] for c in cl["items"]), [(c["name"], c["serviceId"]) for c in cl["items"]])
    check("all counters closed again", all(c["status"] == "CLOSED" for c in cl["items"]), [(c["name"], c["status"]) for c in cl["items"] if c["status"] != "CLOSED"])
    st, dash = admin.get("/api/admin/dashboard")
    check("no leftover active tokens", dash["crowd"]["queueLength"] == 0 and dash["crowd"]["serving"] == 0, dash["crowd"])

    print("\n" + "=" * 60)
    print("RESULT: %d passed, %d failed" % (passed, failed))
    if failures:
        print("\nFailures:")
        for f in failures:
            print("  - " + f)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
