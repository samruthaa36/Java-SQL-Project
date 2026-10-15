-- =====================================================================
--  SmartQueue - one-time Oracle setup  (create the schema owner)
--
--  Run ONCE as a DBA user (SYSTEM or SYS), e.g.
--
--      sqlplus system/<your-system-password>@//localhost:1521/XEPDB1 @database/oracle-setup.sql
--
--  Pick the right service name for your installation:
--      Oracle XE 18c / 21c ......... XEPDB1
--      Oracle 23ai Free ............ FREEPDB1
--      Oracle XE 11g ............... XE
--
--  After this script, start SmartQueue - it creates all tables, sequences
--  and indexes by itself (or run database/schema.sql as the new user).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Oracle 12c and newer (multitenant): create the user inside the PLUGGABLE
-- database (XEPDB1 / FREEPDB1 / ORCLPDB1), not in the root container (CDB$ROOT).
-- If you connected with //localhost:1521/XEPDB1 as shown above you are already
-- there and can skip the next line.
-- ---------------------------------------------------------------------
-- ALTER SESSION SET CONTAINER = XEPDB1;

-- Remove the old user first if you want to start again from zero:
-- DROP USER smartqueue CASCADE;

CREATE USER smartqueue IDENTIFIED BY "smartqueue123"
  DEFAULT TABLESPACE users
  TEMPORARY TABLESPACE temp
  QUOTA UNLIMITED ON users;

-- The only privileges SmartQueue needs:
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO smartqueue;

-- Note: in the root container (CDB$ROOT) Oracle only accepts common users whose
-- name starts with C## - that is why you must work inside the pluggable database.
--
-- Oracle XE 11g note: remove the line "TEMPORARY TABLESPACE temp" if your
-- installation has no tablespace of that name (it normally has).

-- Check:
-- SELECT username, default_tablespace FROM dba_users WHERE username = 'SMARTQUEUE';
