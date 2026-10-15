# SmartQueue – Intelligent Queue & Crowd Management System

An enterprise-ready, zero-framework queue and crowd management system designed for high-throughput public and private service centers (financial institutions, healthcare facilities, government administrations, and educational campuses).

SmartQueue allows customers to issue digital tokens on their mobile devices, track real-time queue position and dynamic wait-time estimates, and receive timely alerts. Counter staff manage token lifecycles through a specialized service console, while administrators monitor crowd densities, analyze throughput metrics, and act on intelligent optimization recommendations.

---

## Architecture & Technology Stack

| Layer | Implementation | Notes |
|---|---|---|
| **Frontend** | HTML5, CSS3 (Custom Design System), Vanilla ES6+ JavaScript | Zero external runtime dependencies, lightweight, responsive across mobile, tablet, and widescreen kiosk displays |
| **Backend** | Java 11+ (Native `com.sun.net.httpserver.HttpServer`, JDBC) | Zero frameworks; raw performance, embedded HTTP server, and robust concurrent transaction handling |
| **Database** | **Oracle Database** (11g XE, 12c, 18c XE, 19c, 21c XE, 23ai Free) | Relational schema utilizing Oracle sequences, constraint enforcement, and indexed queries |
| **Testing** | Python 3 End-to-End Integration Suite | 187 automated checks covering API contracts, concurrency, and security |

---

## Key Features

### 1. Customer Self-Service Portal
* **Digital Token Generation**: Customers issue service-specific tokens (`A001`, `B014`, etc.) from their mobile browsers or walk-in kiosks.
* **Live Status Tracking**: Dynamic real-time updates for current serving token, queue position, and algorithmic estimated waiting time (ETA).
* **Smart Proximity Alerts**: Multi-stage notifications ("Your turn is approaching" and "Counter ready") with audible chimes and visual cues.
* **Feedback & Service Ratings**: Post-service rating collection for staff performance analytics.
* **Priority Handling**: Support for designated priority patrons (senior citizens, individuals with disabilities, urgent cases).

### 2. Counter Staff Console
* **Counter Lifecycle Management**: Open, pause (break), close, and re-assign counters across different service lines.
* **Token Workflow Controls**: Call next customer, start service, mark completed (with resolution notes), skip, recall, or place on hold.
* **Walk-In Registration**: Onboard customers who lack personal smart devices directly at the counter.
* **Keyboard Shortcuts**: Ergonomic physical key shortcuts (`N`: Next, `S`: Start, `C`: Complete, `R`: Recall, `H`: Hold, `K`: Skip) for rapid transaction processing.

### 3. Queue Engine & Predictive ETA
* **Multi-Service & Multi-Counter Routing**: Independent queues per department with flexible counter routing.
* **Fair Ordering & Anti-Starvation**: Strict prioritization (Emergency → Priority → Standard) with configurable starvation caps to prevent standard token starvation.
* **Predictive Wait-Time Modeling**: Real-time counter simulation incorporating rolling historical service durations.
* **Automated No-Show Reclamation**: Configurable timeout background worker that automatically marks non-responsive tokens as skipped.

### 4. Operations & Crowd Control
* **Crowd Density Monitoring**: Real-time crowd classification (Low, Moderate, High, Critical) with automated threshold triggers.
* **Intelligent Optimization Engine**: Dynamic suggestions to re-allocate idle counters to congested service categories with one-click application.
* **Public Lobby Kiosk Board** (`/display.html`): High-visibility presentation display with chime and text-to-speech audio announcements for lobby screens and TVs.

### 5. Administrative Intelligence & Governance
* **Comprehensive Analytics**: Service demand distribution, wait-time trends, counter throughput, peak-hour heatmaps, and staff productivity benchmarks.
* **Audit & Activity Logging**: Complete historical tracking of administrative and staff interventions.
* **Data Portability**: Full data export capabilities in CSV format for institutional reporting.

---

## Project Structure

```
SmartQueue/
├── backend/src/com/smartqueue/
│   ├── Main.java                  Application entry point and daemon scheduler
│   ├── config/AppConfig.java      Configuration provider (application.properties)
│   ├── db/                        JDBC pool, schema migrator, demo-data seeder
│   ├── http/                      HTTP router, session security, static asset server
│   ├── api/                       REST controller layer (Auth, User, Staff, Admin, Public)
│   ├── service/                   Core business logic and domain services
│   │   ├── QueueService           Transactional token lifecycle management
│   │   ├── LiveQueue              Queue simulation, ETA prediction, crowd classification
│   │   ├── OptimizationService    Recommendation algorithms & throughput forecasting
│   │   ├── AnalyticsService       Metric aggregations and reporting engine
│   │   └── ...                    Notification, Settings, ActivityLog, AuthService
│   └── util/                      JSON serialization, PBKDF2 hashing, time utilities
├── frontend/
│   ├── index.html                 Customer authentication and registration
│   ├── user.html                  Customer token dashboard
│   ├── staff.html                 Counter operator console
│   ├── admin.html                 Central administrative and analytics dashboard
│   ├── display.html               Public lobby display board
│   ├── css/style.css              Unified responsive theme (dark/light mode)
│   └── js/                        Modular client application scripts
├── database/
│   ├── schema.sql                 Complete Oracle schema (tables, sequences, indexes)
│   ├── oracle-setup.sql           Initialization script for Oracle database user
│   └── oracle-reset.sql           Development reset utility
├── config/
│   └── application.properties     System and database configuration
├── lib/                           Oracle JDBC driver (ojdbc8.jar)
├── scripts/                       Automation scripts (run.bat, run.sh, build.sh, test.sh)
└── tests/
    └── api_test.py                End-to-end integration test suite
```

---

## Getting Started

### Prerequisites
* **Java Development Kit (JDK) 11 or higher** (`javac -version`)
* **Oracle Database** (Oracle XE 11g/18c/21c, Oracle 19c, or Oracle 23ai Free)
* **Oracle JDBC Driver**: Download `ojdbc8.jar` and place it in the `lib/` directory.

---

### Step 1: Initialize Database User
Connect to your Oracle database instance as `SYSTEM` (or a user with administrative privileges) and run `database/oracle-setup.sql`:

```bash
sqlplus system/<password>@//localhost:1521/XEPDB1 @database/oracle-setup.sql
```

This provisions the designated user schema:
```sql
CREATE USER smartqueue IDENTIFIED BY "smartqueue123"
  DEFAULT TABLESPACE users TEMPORARY TABLESPACE temp QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO smartqueue;
```

> **Note:** The application automatically verifies, generates, and migrates all necessary tables, sequences, and indexes on its initial startup.

---

### Step 2: Configure Environment
Verify the connection credentials in [config/application.properties](config/application.properties):

```properties
# Oracle Database Connection
db.host=localhost
db.port=1521
db.service=XEPDB1       # Use XEPDB1 for XE 18c/21c, FREEPDB1 for 23ai, XE for 11g
db.user=smartqueue
db.password=smartqueue123

# Web Server Configuration
server.port=8080
server.host=0.0.0.0
server.threads=32

# Application Settings
app.seed-demo-data=true # Set to false for clean production deployment
```

---

### Step 3: Launch the Application

#### Windows
Run the provided batch script:
```cmd
scripts\run.bat
```

#### Linux / macOS
Execute the startup shell script:
```bash
chmod +x scripts/*.sh
./scripts/run.sh
```

Navigate to **`http://localhost:8080`** in any modern web browser.

---

## Default System Accounts

When `app.seed-demo-data=true` is enabled, the system initializes with sample role profiles:

| Role | Email | Password | Access Level |
|---|---|---|---|
| **Administrator** | `admin@smartqueue.com` | `Admin@123` | Full system control, analytics, user & counter management |
| **Counter Staff** | `ravi@smartqueue.com` | `Staff@123` | Counter operations, customer calling, ticket completion |
| **Standard Customer** | `user@smartqueue.com` | `User@123` | General service token generation & status monitoring |
| **Priority Customer** | `senior@smartqueue.com` | `User@123` | Priority service token allocation |

---

## Security & Reliability Engineering

* **Server-Enforced Access Control**: Strict role verification (`USER`, `STAFF`, `ADMIN`) validated on every API endpoint.
* **Cryptographic Password Protection**: User credentials hashed via **PBKDF2-HMAC-SHA256** using unique per-user cryptographic salts across 150,000 iterations.
* **Session Hardening**: 256-bit cryptographically secure session tokens stored exclusively as SHA-256 hashes server-side, transmitted with `HttpOnly`, `SameSite=Strict` cookies.
* **SQL Injection Immunity**: Complete parameterized query implementation (`PreparedStatement`) across all database operations.
* **Cross-Site Scripting (XSS) & CSRF Defense**: Strict Content-Security-Policy headers, context-aware DOM sanitization, and `X-Requested-With` header verification for mutation requests.
* **Brute-Force Rate Limiting**: Exponential login lockout triggered after 5 consecutive failed attempts.

---

## REST API Specification

All endpoints are served under `/api` and consume/produce `application/json`.

| Service Scope | Method & Endpoint | Description |
|---|---|---|
| **Authentication** | `POST /api/auth/register` | Register a new customer account |
| | `POST /api/auth/login` | Authenticate and obtain session cookie |
| | `POST /api/auth/logout` | Terminate active user session |
| | `GET /api/auth/session` | Inspect current session context |
| **Public** | `GET /api/public/config` | Retrieve system display configurations |
| | `GET /api/public/services` | List active services and operational statuses |
| | `GET /api/public/board` | Lobby display live feed |
| **Customer** | `GET /api/user/dashboard` | Active ticket status and wait predictions |
| | `POST /api/user/tokens` | Request a new service token |
| | `POST /api/user/tokens/{id}/cancel` | Cancel an active token |
| | `POST /api/user/tokens/{id}/feedback` | Submit service ratings and comments |
| **Staff** | `GET /api/staff/overview` | Active queue status and counter assignment |
| | `POST /api/staff/counter/open\|close\|break` | Update counter operational status |
| | `POST /api/staff/call-next` | Advance queue and summon next customer |
| | `POST /api/staff/tokens/{id}/complete` | Conclude service for active token |
| **Administration** | `GET /api/admin/dashboard` | Aggregated real-time metrics and crowd index |
| | `GET /api/admin/analytics` | Historical trends, wait time distributions |
| | `GET /api/admin/reports/export` | Download operational logs as CSV |
| | `POST /api/admin/recommendations/apply` | Execute operational optimization actions |

---

## Automated Testing & Validation

The application includes an end-to-end regression and functional test suite:

```bash
# 1. Start application service
./scripts/run.sh

# 2. Run API integration tests
python3 tests/api_test.py http://localhost:8080
```

The test harness evaluates 187 automated assertions covering:
* Authentication boundaries and privilege escalation guards
* Queue transactional integrity under concurrency
* Anti-starvation scheduling rules
* ETA algorithmic accuracy
* No-show automatic state transitions

---

## Troubleshooting Guide

| Issue | Root Cause | Resolution |
|---|---|---|
| `The Oracle JDBC driver is missing` | Driver archive missing from classpath | Place `ojdbc8.jar` inside the `lib/` directory |
| `ORA-01017: invalid username/password` | Incorrect credentials supplied | Verify `db.user` and `db.password` in `application.properties` |
| `ORA-12514 / ORA-12505` | Listener cannot locate target service | Verify service identifier with `lsnrctl status` and set `db.service` |
| `ORA-12541: TNS:no listener` | Oracle Listener service offline | Start the Oracle listener service (`lsnrctl start`) |
| `ORA-01950: no privileges on tablespace` | User quota unassigned | Execute `ALTER USER smartqueue QUOTA UNLIMITED ON users;` |
| `Port 8080 is already in use` | Conflicting service binding | Update `server.port` in `config/application.properties` |

---

## License

This project is open-source and distributed under the [MIT License](LICENSE).
