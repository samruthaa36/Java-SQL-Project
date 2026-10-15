-- =====================================================================
--  SmartQueue - start again from zero (Oracle)
--
--  Run as the SMARTQUEUE user (NOT as SYSTEM) to remove every SmartQueue
--  table and sequence.  Stop the application first.  Afterwards simply
--  start SmartQueue again - it re-creates the schema and the demo data.
--
--      sqlplus smartqueue/smartqueue123@//localhost:1521/XEPDB1 @database/oracle-reset.sql
-- =====================================================================
BEGIN
  FOR t IN (SELECT table_name FROM user_tables) LOOP
    EXECUTE IMMEDIATE 'DROP TABLE "' || t.table_name || '" CASCADE CONSTRAINTS PURGE';
  END LOOP;
  FOR s IN (SELECT sequence_name FROM user_sequences) LOOP
    EXECUTE IMMEDIATE 'DROP SEQUENCE "' || s.sequence_name || '"';
  END LOOP;
END;
/
