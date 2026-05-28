-- Query designed to land in V$SQL_PLAN with a mix of TABLE ACCESS FULL
-- and INDEX RANGE SCAN, so the Explain Plan sub-tab can highlight both
-- red (expensive) and green (index) rows.
--
-- Usage:
--   1) Run this query once in a DataGrip console connected as HR.
--   2) Open the Oracle Sessions tool window.
--   3) Select your session row.
--   4) Switch the bottom sub-tab to "Explain Plan".
--      → You should see the tree with both FULL and INDEX nodes,
--        with the FULL row highlighted in red.

SELECT /*+ FULL(e) */
       e.EMPLOYEE_ID,
       e.LAST_NAME,
       e.SALARY,
       d.DEPARTMENT_NAME,
       d.LOCATION
  FROM HR.EMPLOYEES e
  JOIN HR.DEPARTMENTS d
    ON d.DEPARTMENT_ID = e.DEPARTMENT_ID
 WHERE e.SALARY > 15000
   AND e.STATUS = 'ACTIVE'
 ORDER BY e.SALARY DESC;
