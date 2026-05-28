-- Gather optimizer statistics so V$SQL_PLAN shows realistic costs and
-- the Explain Plan tab can highlight FULL vs INDEX paths.

ALTER SESSION SET CONTAINER = FREEPDB1;

BEGIN
  DBMS_STATS.GATHER_TABLE_STATS('HR', 'EMPLOYEES',   cascade => TRUE);
  DBMS_STATS.GATHER_TABLE_STATS('HR', 'DEPARTMENTS', cascade => TRUE);
END;
/
