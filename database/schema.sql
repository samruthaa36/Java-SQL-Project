-- =====================================================================
--  SmartQueue  -  Smart Queue & Crowd Management System
--  ORACLE DATABASE schema   (works on Oracle 11g XE, 12c, 18c, 19c, 21c, 23ai)
--
--  You do NOT have to run this file manually: the Java application
--  creates every table / sequence / index automatically on first start
--  (and silently skips the ones that already exist).
--  It is provided for documentation, viva and manual setup:
--
--      sqlplus smartqueue/YourPassword@//localhost:1521/XEPDB1 @database/schema.sql
--
--  Notes for the viva
--   * Primary keys use SEQUENCES (users_seq.NEXTVAL ...) - works on every Oracle version.
--   * "ENUM" columns are VARCHAR2 + CHECK constraints, booleans are NUMBER(1) + CHECK (0/1).
--   * VARCHAR2(n CHAR) = n characters (not bytes) so names in any language fit.
--   * Oracle treats '' as NULL, therefore optional columns are nullable.
-- =====================================================================

-- ---------------------------------------------------------------------
-- sequences (one per table with a numeric id)
-- ---------------------------------------------------------------------
CREATE SEQUENCE users_seq         START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE services_seq      START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE counters_seq      START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE tokens_seq        START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE notifications_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE activity_log_seq  START WITH 1 INCREMENT BY 1;

-- ---------------------------------------------------------------------
-- users : customers (USER), counter staff (STAFF) and administrators
-- ---------------------------------------------------------------------
CREATE TABLE users (
  id              NUMBER(19)          NOT NULL,
  full_name       VARCHAR2(100 CHAR)  NOT NULL,
  email           VARCHAR2(150 CHAR)  NOT NULL,
  phone           VARCHAR2(20 CHAR),
  password_hash   VARCHAR2(255 CHAR)  NOT NULL,
  role            VARCHAR2(10 CHAR)   DEFAULT 'USER' NOT NULL,
  is_priority     NUMBER(1)           DEFAULT 0 NOT NULL,
  priority_reason VARCHAR2(100 CHAR),
  is_active       NUMBER(1)           DEFAULT 1 NOT NULL,
  created_at      TIMESTAMP(0)        NOT NULL,
  last_login_at   TIMESTAMP(0),
  CONSTRAINT pk_users          PRIMARY KEY (id),
  CONSTRAINT uq_users_email    UNIQUE (email),
  CONSTRAINT ck_users_role     CHECK (role IN ('USER','STAFF','ADMIN')),
  CONSTRAINT ck_users_priority CHECK (is_priority IN (0,1)),
  CONSTRAINT ck_users_active   CHECK (is_active IN (0,1))
);

CREATE INDEX idx_users_role ON users (role);

-- ---------------------------------------------------------------------
-- services : every service has its own queue and token prefix
-- ---------------------------------------------------------------------
CREATE TABLE services (
  id                  NUMBER(19)          NOT NULL,
  name                VARCHAR2(80 CHAR)   NOT NULL,
  prefix              VARCHAR2(3 CHAR)    NOT NULL,
  description         VARCHAR2(255 CHAR),
  icon                VARCHAR2(30 CHAR)   DEFAULT 'ticket' NOT NULL,
  color               VARCHAR2(9 CHAR)    DEFAULT '#4f46e5' NOT NULL,
  avg_service_minutes NUMBER(5,2)         DEFAULT 6 NOT NULL,
  is_active           NUMBER(1)           DEFAULT 1 NOT NULL,
  priority_streak     NUMBER(10)          DEFAULT 0 NOT NULL,
  created_at          TIMESTAMP(0)        NOT NULL,
  CONSTRAINT pk_services        PRIMARY KEY (id),
  CONSTRAINT uq_services_name   UNIQUE (name),
  CONSTRAINT uq_services_prefix UNIQUE (prefix),
  CONSTRAINT ck_services_active CHECK (is_active IN (0,1))
);

-- ---------------------------------------------------------------------
-- counters : service desks operated by staff
-- ---------------------------------------------------------------------
CREATE TABLE counters (
  id          NUMBER(19)         NOT NULL,
  name        VARCHAR2(60 CHAR)  NOT NULL,
  service_id  NUMBER(19)         NOT NULL,
  status      VARCHAR2(10 CHAR)  DEFAULT 'CLOSED' NOT NULL,
  staff_id    NUMBER(19),
  opened_at   TIMESTAMP(0),
  is_deleted  NUMBER(1)          DEFAULT 0 NOT NULL,
  created_at  TIMESTAMP(0)       NOT NULL,
  CONSTRAINT pk_counters         PRIMARY KEY (id),
  CONSTRAINT fk_counters_service FOREIGN KEY (service_id) REFERENCES services (id),
  CONSTRAINT fk_counters_staff   FOREIGN KEY (staff_id)   REFERENCES users (id) ON DELETE SET NULL,
  CONSTRAINT ck_counters_status  CHECK (status IN ('CLOSED','OPEN','BREAK')),
  CONSTRAINT ck_counters_deleted CHECK (is_deleted IN (0,1))
);

CREATE INDEX idx_counters_service ON counters (service_id);
CREATE INDEX idx_counters_staff   ON counters (staff_id);

-- ---------------------------------------------------------------------
-- tokens : one row per queue ticket (the heart of the system)
--   priority: 0 = normal, 1 = priority, 2 = emergency
-- ---------------------------------------------------------------------
CREATE TABLE tokens (
  id                 NUMBER(19)          NOT NULL,
  service_id         NUMBER(19)          NOT NULL,
  token_seq          NUMBER(10)          NOT NULL,
  token_code         VARCHAR2(12 CHAR)   NOT NULL,
  token_date         DATE                NOT NULL,
  user_id            NUMBER(19),
  customer_name      VARCHAR2(100 CHAR)  NOT NULL,
  customer_phone     VARCHAR2(20 CHAR),
  priority           NUMBER(5)           DEFAULT 0 NOT NULL,
  priority_reason    VARCHAR2(100 CHAR),
  status             VARCHAR2(10 CHAR)   DEFAULT 'WAITING' NOT NULL,
  source             VARCHAR2(10 CHAR)   DEFAULT 'ONLINE' NOT NULL,
  counter_id         NUMBER(19),
  issued_by          NUMBER(19),
  served_by          NUMBER(19),
  notes              VARCHAR2(255 CHAR),
  created_at         TIMESTAMP(0)        NOT NULL,
  queue_time         TIMESTAMP(0)        NOT NULL,
  called_at          TIMESTAMP(0),
  last_called_at     TIMESTAMP(0),
  serving_at         TIMESTAMP(0),
  ended_at           TIMESTAMP(0),
  service_seconds    NUMBER(10)          DEFAULT 0 NOT NULL,
  call_count         NUMBER(10)          DEFAULT 0 NOT NULL,
  estimated_wait_min NUMBER(10),
  approach_notified  NUMBER(1)           DEFAULT 0 NOT NULL,
  next_notified      NUMBER(1)           DEFAULT 0 NOT NULL,
  close_reason       VARCHAR2(120 CHAR),
  rating             NUMBER(5),
  feedback           VARCHAR2(300 CHAR),
  CONSTRAINT pk_tokens          PRIMARY KEY (id),
  CONSTRAINT uq_token_seq       UNIQUE (service_id, token_date, token_seq),
  CONSTRAINT fk_tokens_service  FOREIGN KEY (service_id) REFERENCES services (id),
  CONSTRAINT fk_tokens_user     FOREIGN KEY (user_id)    REFERENCES users (id),
  CONSTRAINT fk_tokens_counter  FOREIGN KEY (counter_id) REFERENCES counters (id),
  CONSTRAINT fk_tokens_issued   FOREIGN KEY (issued_by)  REFERENCES users (id),
  CONSTRAINT fk_tokens_served   FOREIGN KEY (served_by)  REFERENCES users (id),
  CONSTRAINT ck_tokens_status   CHECK (status IN ('WAITING','CALLED','SERVING','ON_HOLD','COMPLETED','SKIPPED','CANCELLED')),
  CONSTRAINT ck_tokens_source   CHECK (source IN ('ONLINE','WALK_IN')),
  CONSTRAINT ck_tokens_flags    CHECK (approach_notified IN (0,1) AND next_notified IN (0,1))
);

CREATE INDEX idx_tokens_status  ON tokens (status, service_id);
CREATE INDEX idx_tokens_user    ON tokens (user_id, status);
CREATE INDEX idx_tokens_created ON tokens (created_at);
CREATE INDEX idx_tokens_date    ON tokens (token_date);
CREATE INDEX idx_tokens_counter ON tokens (counter_id);

-- ---------------------------------------------------------------------
-- notifications : in-app notifications for every role
-- ---------------------------------------------------------------------
CREATE TABLE notifications (
  id          NUMBER(19)          NOT NULL,
  user_id     NUMBER(19)          NOT NULL,
  token_id    NUMBER(19),
  type        VARCHAR2(30 CHAR)   NOT NULL,
  title       VARCHAR2(120 CHAR)  NOT NULL,
  message     VARCHAR2(300 CHAR)  NOT NULL,
  is_read     NUMBER(1)           DEFAULT 0 NOT NULL,
  created_at  TIMESTAMP(0)        NOT NULL,
  CONSTRAINT pk_notifications PRIMARY KEY (id),
  CONSTRAINT fk_notif_user    FOREIGN KEY (user_id)  REFERENCES users (id)  ON DELETE CASCADE,
  CONSTRAINT fk_notif_token   FOREIGN KEY (token_id) REFERENCES tokens (id) ON DELETE SET NULL,
  CONSTRAINT ck_notif_read    CHECK (is_read IN (0,1))
);

CREATE INDEX idx_notif_user ON notifications (user_id, is_read, id);

-- ---------------------------------------------------------------------
-- sessions : login sessions (only a SHA-256 hash of the token is stored)
-- ---------------------------------------------------------------------
CREATE TABLE sessions (
  token_hash  VARCHAR2(64 CHAR)   NOT NULL,
  user_id     NUMBER(19)          NOT NULL,
  created_at  TIMESTAMP(0)        NOT NULL,
  expires_at  TIMESTAMP(0)        NOT NULL,
  ip          VARCHAR2(45 CHAR),
  user_agent  VARCHAR2(200 CHAR),
  CONSTRAINT pk_sessions      PRIMARY KEY (token_hash),
  CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_sessions_user    ON sessions (user_id);
CREATE INDEX idx_sessions_expires ON sessions (expires_at);

-- ---------------------------------------------------------------------
-- settings : admin-configurable rules (crowd levels, priority rules ...)
-- ---------------------------------------------------------------------
CREATE TABLE settings (
  setting_key    VARCHAR2(60 CHAR)   NOT NULL,
  setting_value  VARCHAR2(255 CHAR)  NOT NULL,
  description    VARCHAR2(255 CHAR),
  CONSTRAINT pk_settings PRIMARY KEY (setting_key)
);

-- ---------------------------------------------------------------------
-- activity_log : audit trail of important actions
-- ---------------------------------------------------------------------
CREATE TABLE activity_log (
  id           NUMBER(19)          NOT NULL,
  actor_id     NUMBER(19),
  actor_name   VARCHAR2(100 CHAR)  NOT NULL,
  action       VARCHAR2(50 CHAR)   NOT NULL,
  entity_type  VARCHAR2(30 CHAR),
  entity_id    NUMBER(19),
  details      VARCHAR2(500 CHAR),
  created_at   TIMESTAMP(0)        NOT NULL,
  CONSTRAINT pk_activity_log PRIMARY KEY (id)
);

CREATE INDEX idx_activity_created ON activity_log (created_at);
