# SmartQueue – Smart Queue & Crowd Management System

A complete, working queue-management web application for service centres (government offices, banks, hospitals, colleges…).
Customers take a **digital token** from their phone, watch their position and waiting time live and get alerted when their turn is near.
Counter staff call and serve customers from a console, administrators manage everything and watch crowd levels, analytics and smart recommendations.

| Layer | Technology |
|---|---|
| Frontend | HTML5, CSS3, vanilla JavaScript (no frameworks, no CDN – works offline) |
| Backend | Java 11+ (JDK built-in `HttpServer`, JDBC, **no frameworks**) |
| Database | **Oracle Database** (11g XE, 12c, 18c XE, 19c, 21c XE, 23ai Free) |

---

## 1. Quick start (5 minutes)

### Requirements
* **JDK 11 or newer** (`javac -version`)
* **Oracle Database** running locally – the free **Oracle Database XE** is perfect (11g XE, 18c XE, 21c XE or 23ai Free; 12c / 19c editions work too)
* **Oracle JDBC driver** jar (`ojdbc8.jar`, one file, see step 2)

### Step 1 – Create the database user (once)
SmartQueue keeps its tables in its own Oracle user (schema). Connect as `SYSTEM` (to the *pluggable* database – `XEPDB1` for XE 18c/21c, `FREEPDB1` for 23ai Free, `XE` for 11g XE) and run:

```bash
sqlplus system/<your-system-password>@//localhost:1521/XEPDB1 @database/oracle-setup.sql
```
which executes

```sql
CREATE USER smartqueue IDENTIFIED BY "smartqueue123"
  DEFAULT TABLESPACE users TEMPORARY TABLESPACE temp QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO smartqueue;
```
The application then creates all **tables, sequences, indexes and demo data by itself** on the first start. (Prefer to create the tables manually? Run `database/schema.sql` as the `smartqueue` user – it contains the full schema.)

### Step 2 – Put the Oracle driver into `lib/`
Download **ojdbc8.jar** from <https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html> (or from Maven Central: `com/oracle/database/jdbc/ojdbc8`) and copy the `.jar` into the `lib/` folder. See `lib/README.txt`. (Oracle's licence does not allow shipping the driver inside this project.)

### Step 3 – Configure
Open `config/application.properties` and check the connection data:

```properties
db.host=localhost
db.port=1521
db.service=XEPDB1       # XE 18c/21c = XEPDB1 | 23ai Free = FREEPDB1 | XE 11g = XE
db.user=smartqueue
db.password=smartqueue123   # <- the password you used in oracle-setup.sql
server.port=8080
```
Only the database settings differ from the MySQL edition – everything else (UI, features, API, demo data, accounts) is identical. Using an old-style SID? Set `db.sid=XE` instead of `db.service`. Need a special connection string (RAC, TCPS, wallet…)? Set `db.url=jdbc:oracle:thin:@…`.

### Step 4 – Run
| OS | Command |
|---|---|
| Windows | double-click **`scripts\run.bat`** |
| Linux / macOS | `./scripts/run.sh` |

Then open **<http://localhost:8080>**.
The console prints an extra address like `http://192.168.1.20:8080` – open it on your phone (same Wi-Fi) to try the customer experience on a real device.

### Demo accounts (created on first start)
| Role | Email | Password |
|---|---|---|
| Administrator | `admin@smartqueue.com` | `Admin@123` |
| Counter staff | `ravi@smartqueue.com` (also `meena@`, `arjun@`, `divya@`, `karthik@`, `sana@` `@smartqueue.com`) | `Staff@123` |
| Customer | `user@smartqueue.com` | `User@123` |
| Customer (priority – senior citizen) | `senior@smartqueue.com` | `User@123` |

The login page has one-click buttons for these. **Change them (or set `app.seed-demo-data=false` before the very first start) for real use.**

---

## 2. Five-minute demo script (great for presentations / viva)

1. **Window A** – log in as *Customer* (`user@smartqueue.com`), take a token for *General Services*. See position, people ahead, estimated wait.
2. **Window B** (private window) – log in as *Staff* (`ravi@smartqueue.com`), press **Open this counter**, then **Call next customer**.
3. Back in **Window A**: the green *"It's your turn!"* banner appears with a countdown, a toast, a sound (click the speaker icon to enable) and a bell notification.
4. In **B** press **Start serving**, then **Complete**. In **A** the rating card appears.
5. Log in as *Administrator* – open **Dashboard** (live KPIs, crowd gauge), **Settings → Demo tools → Simulate a crowd** and watch crowd level change to MODERATE/HIGH, recommendations appear (*"Open Counter 3…"* with **Apply now**), **Analytics** and **Reports → Export CSV**.
6. Open **<http://localhost:8080/display.html>** on a second screen / TV – the public lobby board with live "Now serving".
7. Try the **dark mode** toggle and resize to phone width.

---

## 3. Features (mapped to the project specification)

### User module
* Register / login / logout, profile, change password, secure sessions
* Choose a service and get a **digital token** (prefix per service: `A001`, `B014`…)
* Live **now-serving**, **people ahead**, **queue position**, **estimated waiting time** (auto-refresh every 3 s)
* **"Your turn is approaching"** alert (configurable: when ≤ N people ahead) + **"It's your turn"** call banner with no-show countdown
* Cancel token, full **token history** with filters, rate the service
* Priority customers (senior citizens / disabled) flagged by the admin are served ahead of normal tokens

### Smart token system
* Unique token per service with its own letter prefix
* Statuses: **Waiting → Called → Serving → Completed**, plus **Skipped**, **Cancelled**, **On hold**
* **No duplicate active tokens** per customer (enforced in the database transaction)

### Smart queue management
* FIFO within a priority class, **Emergency → Priority → Normal** ordering with an **anti-starvation rule** (after *N* priority tokens in a row one normal token is served – configurable)
* **Multiple queues** (one per service), **multiple counters** per service
* **Auto-skip no-shows** after a configurable timeout (background job), restore skipped customers
* Manual **re-ordering** (move to front/back), change priority, hold/resume, auto-assign mode

### Waiting-time prediction
Discrete simulation of the open counters: every counter becomes free after the remaining time of its current customer and each waiting customer goes to the counter that frees up first. Service time per service = blend of the configured default and the **real average of the last 14 days**. The Analytics page shows the **prediction accuracy** (promised vs. actual wait).

### Counter management
Create/edit/delete counters, assign to services, open/close, put on break, re-assign a counter to another service, see who is serving what.

### Staff module
Counter console with: open/close counter, **call next**, start serving, complete (with notes), **skip**, **recall** (re-announce), **hold**, cancel, walk-in registration, waiting/on-hold/skipped lists, today's log, own statistics. Keyboard shortcuts **N / S / C / R / H / K**.

### Admin module
Users, staff and services management, counters, live queues (reorder/prioritise/cancel/add walk-ins), crowd monitoring, **priority rules**, reports with filters + **CSV export**, activity/audit log, system settings (crowd thresholds, no-show timeout, priority burst limit, queue limit, public registration, session length).

### Crowd management
| Queue length | Level |
|---|---|
| 0 – 5 | **LOW** |
| 6 – 15 | **MODERATE** |
| 16 – 30 | **HIGH** |
| 31+ | **CRITICAL** |

(thresholds are editable in *Settings*). Shown as colour pills for users, a gauge for admins and on the lobby display.

### Analytics dashboard
Visitors per day / hour, completion vs. cancelled vs. skipped, average wait & service time, service-wise demand (donut), peak hours, busiest weekdays, **crowd heat-map** (weekday × hour), service / counter / staff performance, ratings, busiest periods. Range selectable 7–90 days. All charts are hand-built SVG.

### Notifications
In-app bell with unread counter, toast pop-ups, optional sound + vibration; events: token created, next-in-line, turn approaching, called, recalled, on hold, skipped, cancelled, completed.

### Optimisation recommendations
Rule-based engine that suggests *open a counter*, *move a counter from a calm service to a busy one*, *add capacity*, and flags quiet services – each with an **Apply now** button that executes the change. Plus an hour-by-hour **visitor forecast** from the same weekday in previous weeks.

### Extras beyond the specification
Public **lobby display board** (`/display.html`) with announcements (chime + speech), **light/dark theme**, fully responsive (phones → TV), demo tools (simulate a crowd), activity log, ratings & feedback, one-click demo login, password-strength meter, login lockout, graceful offline indicator, print styles.

---

## 4. Project structure

```
SmartQueue/
├── backend/src/com/smartqueue/
│   ├── Main.java                  start-up, config, scheduler
│   ├── config/AppConfig.java      reads config/application.properties
│   ├── db/                        Oracle JDBC pool, schema creation, demo-data seeder, Row helper
│   ├── http/                      HttpServer wrapper, router, request context, static files, security headers
│   ├── api/                       REST endpoints  (Auth / User / Staff / Admin / Public)
│   ├── service/                   business logic
│   │   ├── QueueService           token life-cycle (transactional, one global lock)
│   │   ├── LiveQueue              ordering, ETA simulation, crowd level  ← the "smart engine"
│   │   ├── OptimizationService    recommendations + forecast
│   │   ├── AnalyticsService       statistics for charts / reports
│   │   ├── NotificationService, ActivityLog, Settings, AuthService, BackgroundJobs …
│   └── util/                      JSON parser/writer, password hashing, time helpers, logging
├── frontend/
│   ├── index.html                 login / register
│   ├── user.html   staff.html   admin.html   display.html
│   ├── css/style.css              design system (light + dark)
│   └── js/                        core.js (SQ library), charts.js, per-page scripts
├── database/schema.sql            complete Oracle schema (tables, sequences, indexes)
├── database/oracle-setup.sql      one-time: create the 'smartqueue' Oracle user
├── database/oracle-reset.sql      optional: drop all tables/sequences to start again
├── config/application.properties
├── lib/                           put ojdbc8.jar here
├── scripts/                       run.sh / run.bat / build.sh / test.sh
└── tests/api_test.py              end-to-end API tests (187 checks)
```

### Database (8 tables, 6 sequences)
`users`, `services`, `counters`, `tokens` (status, priority, position, timestamps, rating), `notifications`, `sessions`, `settings`, `activity_log`. Foreign keys, CHECK constraints and indexes on the hot columns (service/status, user/status, created time). Primary keys come from **Oracle sequences** (`users_seq.NEXTVAL` …), so the schema works unchanged on every Oracle version from 11g to 23ai.

**What was adapted for Oracle** (the rest of the application is untouched):
* `AUTO_INCREMENT` → sequences · `ENUM` → `VARCHAR2` + `CHECK` · `BOOLEAN` → `NUMBER(1)` + `CHECK (0,1)` · `DATETIME` → `TIMESTAMP(0)` · `VARCHAR(n)` → `VARCHAR2(n CHAR)`
* `LIMIT / OFFSET` → `ROWNUM` pagination (works on 11g, unlike `FETCH FIRST`)
* Oracle stores `''` as `NULL` and compares text case-sensitively → optional columns are nullable and searches use `LOWER(...) LIKE`
* JDBC URL `jdbc:oracle:thin:@//host:port/service`; tables/sequences are created with "skip if it already exists" handling (Oracle has no `CREATE … IF NOT EXISTS`)

### Request flow
Browser → `fetch('/api/…')` (JSON, cookie session) → `Router` (role check) → `api/*` → `service/*` (PreparedStatement SQL, transaction for every queue change) → JSON → UI re-renders only if the HTML changed.

---

## 5. Security
* **Role-based access** (USER / STAFF / ADMIN) enforced on every endpoint on the server, not only in the UI
* Passwords hashed with **PBKDF2-HMAC-SHA256** (per-user random salt, 150 000 iterations); never stored or logged in clear text
* Session token = 256-bit random; only its **SHA-256 hash** is stored; cookie is `HttpOnly; SameSite=Strict`; logout and expiry invalidate it server-side
* **PreparedStatement** for all SQL (no string-built queries) → no SQL injection
* All user text is escaped in the UI (`SQ.html` template) → no XSS; strict **Content-Security-Policy** (`script-src 'self'`) and other security headers
* **CSRF** protection: state-changing requests must carry `X-Requested-With: SmartQueue` (the frontend does this automatically; if you call the API with curl add the header or use a Bearer token)
* **Login lock-out** (5 failed attempts within 10 minutes lock the account for 5 minutes), input validation on every field (length, format, ranges)

---

## 6. Testing
A stdlib-only Python test-suite exercises the real HTTP API, the database and the queue engine (registration, validation, security, token life-cycle, priority order, anti-starvation, ETA, walk-ins, hold/skip/recall/requeue, no-show auto-skip, analytics, reports, CSV, optimisation, notifications, cleanup):

```bash
./scripts/run.sh                                  # terminal 1 – on a FRESH database
python3 tests/api_test.py http://localhost:8080 --slow     # terminal 2  (187 checks)
```
`--slow` additionally waits for the no-show timeout. Result during development: **187 passed, 0 failed**. The UI was verified in a real headless Chromium (login, customer flow, staff console, every admin page, lobby display, dark mode, phone width) with zero console errors.

> **Honest note about how this edition was tested.** The development machine had no Oracle Database server and no access to Oracle's driver download, so the full automated suite (187 checks) and the browser tests were run against **H2 2.x in `MODE=Oracle`** – an Oracle-compatible engine that has the same sequences, `ROWNUM`, `VARCHAR2`/`NUMBER`/`TIMESTAMP` types, `''`→`NULL` behaviour and upper-case column names. The SQL was additionally parsed with an Oracle SQL parser and checked against Oracle's reserved words. What could **not** be proven without a real server is the behaviour of Oracle's own `ojdbc` driver (e.g. batch binding, generated-key retrieval, DATE metadata). The code deliberately uses only conservative, documented JDBC calls for those, but **run the 187-check suite once on your own Oracle** (commands above) – if anything differs, the start-up log and the test report will show exactly which statement to adjust. The same tests passed 187/187 on the original MySQL (MariaDB) edition.

---

## 7. Troubleshooting
| Message | Fix |
|---|---|
| `The Oracle JDBC driver is missing` | Put `ojdbc8.jar` in `lib/` (only one ojdbc jar at a time) |
| `ORA-01017` – refused the user name or password | Edit `db.user` / `db.password`; create the user with `database/oracle-setup.sql` |
| `ORA-12514` / `ORA-12505` / `ORA-12154` – listener does not know the service | Fix `db.service` (XE 11g: `XE` · XE 18c/21c: `XEPDB1` · 23ai Free: `FREEPDB1`); `lsnrctl status` lists the real names. Or set `db.sid` |
| `Cannot reach Oracle` / `ORA-12541` | Start the Oracle service **and its listener**; check `db.host` / `db.port` (default 1521) |
| `ORA-01950` / `ORA-01536` – no privileges / quota on tablespace | Run `ALTER USER smartqueue QUOTA UNLIMITED ON users;` (included in `oracle-setup.sql`) |
| `ORA-01031` – insufficient privileges | `GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO smartqueue;` |
| `ORA-65096` (invalid common user name) | You are in the root container – connect to the pluggable DB (`XEPDB1` / `FREEPDB1`) |
| `Port 8080 is already in use` | Change `server.port` |
| Phone cannot open the LAN address | Allow Java through the firewall; keep `server.host=0.0.0.0` |
| Want a clean demo again | Stop the app, run `database/oracle-reset.sql` as the `smartqueue` user, start the app again (demo data is re-created) |
| Wrong time zone | Set `app.timezone=Asia/Kolkata` (any Java zone id) |

Everything the server does is logged to the console (start-up, scheduler, errors).

---

## 8. REST API (summary)
Base path `/api`. JSON in/out. Errors: `{"error": "message"}` with a proper HTTP status (400 validation, 401 not signed in, 403 forbidden, 404, 409 conflict, 429 too many attempts).

| Area | Endpoints |
|---|---|
| Auth | `POST auth/register`, `auth/login`, `auth/logout` · `GET auth/session`, `auth/me` · `PUT auth/profile` · `POST auth/change-password` |
| Public | `GET public/config`, `public/services`, `public/board` (lobby display) |
| Customer | `GET user/dashboard` · `POST user/tokens` · `GET user/tokens/{id}` · `POST user/tokens/{id}/cancel`, `…/feedback` · `GET user/history` |
| Notifications | `GET notifications` · `POST notifications/read-all`, `notifications/{id}/read` · `DELETE notifications` |
| Staff | `GET staff/overview`, `staff/history` · `POST staff/counter/open|close|break`, `staff/call-next`, `staff/walk-in`, `staff/tokens/{id}/call|start|complete|skip|recall|hold|resume|requeue|priority|move|cancel` |
| Admin | `GET admin/dashboard|queues|analytics|reports|reports/export|recommendations|activity|settings` · `POST admin/recommendations/apply` · CRUD `admin/users`, `admin/services`, `admin/counters` (+ `open|close|reassign`, `reset-password`) · `PUT admin/settings` · `POST admin/demo/simulate-crowd|clear-waiting` |

---

## 9. Viva / explanation cheat-sheet
* **Why Java without Spring?** To show the fundamentals: HTTP server, routing, JDBC pooling, JSON and security are implemented explicitly; zero dependencies except the Oracle JDBC driver.
* **How is concurrency handled?** Every queue-changing operation runs inside a DB transaction guarded by one application-level lock, so two counters can never call the same token and a customer can never hold two active tokens.
* **How is the waiting time computed?** Simulation of open counters (see §3); improves with real data.
* **How is starvation of normal customers prevented?** After *N* consecutive priority tokens one normal token is served (setting *Priority burst limit*).
* **What happens if a customer does not show up?** After *No-show timeout* minutes the background job marks the token *Skipped*; staff can restore it to the end of the queue.
* **How is the system secured?** See §5.
* **How could it scale?** Replace polling with WebSockets/SSE, move the lock to row-level locking per service, add a read replica (Oracle Active Data Guard) for analytics, add SMS/e-mail providers behind `NotificationService`.

---
Made with ❤ for the *Smart Queue & Crowd Management System* project.
