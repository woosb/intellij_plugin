-- Extra standalone procedures + functions to fill the Routines tab for testing.
-- Goal: cover a wide spread of signatures so the plugin's routine list / parameter
-- rendering / DDL tab / Execute tab all have varied rows to show.
--   * IN / OUT / IN OUT params, DEFAULT values
--   * NUMBER / VARCHAR2 / DATE / BOOLEAN / SYS_REFCURSOR return + param types
--   * deterministic function, pipelined-ish cursor function, no-arg routines
-- All objects are HR.-qualified and compile clean (the deliberately broken one
-- lives in 03_buggy_proc.sql).

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ── Functions ───────────────────────────────────────────────────────────────

-- 1) Simple scalar, no args.
CREATE OR REPLACE FUNCTION HR.HEADCOUNT
RETURN NUMBER IS
  l_cnt NUMBER;
BEGIN
  SELECT COUNT(*) INTO l_cnt FROM HR.EMPLOYEES;
  RETURN l_cnt;
END HEADCOUNT;
/

-- 2) DETERMINISTIC, single IN param, arithmetic only (good Execute-tab demo).
CREATE OR REPLACE FUNCTION HR.ANNUAL_SALARY (p_monthly IN NUMBER)
RETURN NUMBER DETERMINISTIC IS
BEGIN
  RETURN NVL(p_monthly, 0) * 12;
END ANNUAL_SALARY;
/

-- 3) Two IN params, DATE math, returns NUMBER.
CREATE OR REPLACE FUNCTION HR.YEARS_OF_SERVICE (
  p_emp_id  IN NUMBER,
  p_as_of   IN DATE DEFAULT SYSDATE
) RETURN NUMBER IS
  l_hire DATE;
BEGIN
  SELECT HIRE_DATE INTO l_hire FROM HR.EMPLOYEES WHERE EMPLOYEE_ID = p_emp_id;
  RETURN TRUNC(MONTHS_BETWEEN(p_as_of, l_hire) / 12, 1);
EXCEPTION
  WHEN NO_DATA_FOUND THEN RETURN NULL;
END YEARS_OF_SERVICE;
/

-- 4) Returns VARCHAR2, CASE / decode style branching.
CREATE OR REPLACE FUNCTION HR.SALARY_GRADE (p_salary IN NUMBER)
RETURN VARCHAR2 IS
BEGIN
  RETURN CASE
           WHEN p_salary IS NULL     THEN 'UNKNOWN'
           WHEN p_salary >= 15000    THEN 'A'
           WHEN p_salary >= 9000     THEN 'B'
           WHEN p_salary >= 5000     THEN 'C'
           ELSE                           'D'
         END;
END SALARY_GRADE;
/

-- 5) Returns BOOLEAN (PL/SQL-only type — nice edge case for param rendering).
CREATE OR REPLACE FUNCTION HR.IS_ACTIVE (p_emp_id IN NUMBER)
RETURN BOOLEAN IS
  l_status HR.EMPLOYEES.STATUS%TYPE;
BEGIN
  SELECT STATUS INTO l_status FROM HR.EMPLOYEES WHERE EMPLOYEE_ID = p_emp_id;
  RETURN l_status = 'ACTIVE';
EXCEPTION
  WHEN NO_DATA_FOUND THEN RETURN FALSE;
END IS_ACTIVE;
/

-- 6) Returns a SYS_REFCURSOR (result-set function).
CREATE OR REPLACE FUNCTION HR.DEPT_EMPLOYEES (p_dept_id IN NUMBER)
RETURN SYS_REFCURSOR IS
  l_cur SYS_REFCURSOR;
BEGIN
  OPEN l_cur FOR
    SELECT EMPLOYEE_ID, FIRST_NAME, LAST_NAME, SALARY
    FROM   HR.EMPLOYEES
    WHERE  DEPARTMENT_ID = p_dept_id
    ORDER  BY SALARY DESC;
  RETURN l_cur;
END DEPT_EMPLOYEES;
/

-- ── Procedures ──────────────────────────────────────────────────────────────

-- 7) No args, just DBMS_OUTPUT.
CREATE OR REPLACE PROCEDURE HR.PRINT_TOP_EARNERS IS
BEGIN
  FOR rec IN (
    SELECT FIRST_NAME, LAST_NAME, SALARY
    FROM   HR.EMPLOYEES
    ORDER  BY SALARY DESC
    FETCH FIRST 5 ROWS ONLY
  ) LOOP
    DBMS_OUTPUT.PUT_LINE(
      RPAD(rec.FIRST_NAME || ' ' || rec.LAST_NAME, 35) || rec.SALARY);
  END LOOP;
END PRINT_TOP_EARNERS;
/

-- 8) IN + OUT, returns a computed value through OUT param.
CREATE OR REPLACE PROCEDURE HR.GET_DEPT_STATS (
  p_dept_id   IN  NUMBER,
  p_headcount OUT NUMBER,
  p_avg_sal   OUT NUMBER,
  p_max_sal   OUT NUMBER
) IS
BEGIN
  SELECT COUNT(*), ROUND(AVG(SALARY), 2), MAX(SALARY)
    INTO p_headcount, p_avg_sal, p_max_sal
    FROM HR.EMPLOYEES
   WHERE DEPARTMENT_ID = p_dept_id;
END GET_DEPT_STATS;
/

-- 9) IN OUT param + DEFAULT — exercises every parameter mode in one signature.
CREATE OR REPLACE PROCEDURE HR.APPLY_RAISE (
  p_emp_id   IN     NUMBER,
  p_pct      IN     NUMBER  DEFAULT 5,
  p_new_sal  IN OUT NUMBER
) IS
BEGIN
  UPDATE HR.EMPLOYEES
     SET SALARY = ROUND(SALARY * (1 + p_pct / 100), 2)
   WHERE EMPLOYEE_ID = p_emp_id
  RETURNING SALARY INTO p_new_sal;
END APPLY_RAISE;
/

-- 10) Mutating proc with explicit COMMIT-less DML + exception block.
CREATE OR REPLACE PROCEDURE HR.DEACTIVATE_EMPLOYEE (
  p_emp_id  IN  NUMBER,
  p_done    OUT VARCHAR2
) IS
BEGIN
  UPDATE HR.EMPLOYEES
     SET STATUS = 'INACTIVE'
   WHERE EMPLOYEE_ID = p_emp_id;
  p_done := CASE WHEN SQL%ROWCOUNT > 0 THEN 'OK' ELSE 'NOT_FOUND' END;
EXCEPTION
  WHEN OTHERS THEN
    p_done := 'ERROR: ' || SQLERRM;
END DEACTIVATE_EMPLOYEE;
/

-- 11) Cursor IN param (REF CURSOR as input) — exotic signature for rendering test.
CREATE OR REPLACE PROCEDURE HR.DUMP_CURSOR (p_cur IN SYS_REFCURSOR) IS
  l_id   NUMBER;
  l_name VARCHAR2(60);
BEGIN
  LOOP
    FETCH p_cur INTO l_id, l_name;
    EXIT WHEN p_cur%NOTFOUND;
    DBMS_OUTPUT.PUT_LINE(l_id || ' - ' || l_name);
  END LOOP;
  CLOSE p_cur;
END DUMP_CURSOR;
/
