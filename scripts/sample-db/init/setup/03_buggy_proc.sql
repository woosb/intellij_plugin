-- Deliberately broken PL/SQL to show off the Source-tab error highlighting.
-- This compiles with errors (PLS-00201 + PLS-00103) — leave it like that.
--
-- After load, ALL_ERRORS will have rows for HR.BUGGY_PROC; the plugin renders
-- them as red line backgrounds + right-side error stripe in the Source tab.

ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE OR REPLACE PROCEDURE HR.BUGGY_PROC (p_id IN NUMBER) AS
  l_count NUMBER;
BEGIN
  l_unknown := p_id;                       -- intentional: undeclared identifier
  SELECT COUNT(*) INTO l_count
  FROM   HR.EMPLOYEES
  WHERE  EMPLOYEE_ID = p_id                -- intentional: missing ; below
  IF l_count > 0 THEN
    DBMS_OUTPUT.PUT_LINE('found employee ' || p_id);
  END IF;
END;
/

-- A clean function so screenshots can also show a healthy Source tab.
CREATE OR REPLACE FUNCTION HR.GET_FULL_NAME (p_id IN NUMBER)
RETURN VARCHAR2 IS
  l_full VARCHAR2(60);
BEGIN
  SELECT FIRST_NAME || ' ' || LAST_NAME
    INTO l_full
    FROM HR.EMPLOYEES
   WHERE EMPLOYEE_ID = p_id;
  RETURN l_full;
EXCEPTION
  WHEN NO_DATA_FOUND THEN
    RETURN NULL;
END;
/

-- Standalone procedure with IN + OUT (good for the Execute-tab screenshot).
CREATE OR REPLACE PROCEDURE HR.ADD_JOB_HISTORY (
  p_emp_id  IN  NUMBER,
  p_job_id  IN  VARCHAR2,
  p_changed OUT VARCHAR2
) IS
BEGIN
  UPDATE HR.EMPLOYEES
     SET JOB_ID = p_job_id
   WHERE EMPLOYEE_ID = p_emp_id;
  p_changed := CASE WHEN SQL%ROWCOUNT > 0 THEN 'YES' ELSE 'NO' END;
END;
/
