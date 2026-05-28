-- Seed data for screenshots: 20 departments + 1500 employees.
-- Enough rows for the Data tab to show "Page 1 · Rows 1–500 (more)".

ALTER SESSION SET CONTAINER = FREEPDB1;

-- Departments (20)
INSERT INTO HR.DEPARTMENTS (DEPARTMENT_ID, DEPARTMENT_NAME, LOCATION)
SELECT
  9 + LEVEL,
  CASE MOD(LEVEL, 5)
    WHEN 0 THEN 'Engineering ' || LEVEL
    WHEN 1 THEN 'Sales '       || LEVEL
    WHEN 2 THEN 'Finance '     || LEVEL
    WHEN 3 THEN 'HR '          || LEVEL
    ELSE        'Operations '  || LEVEL
  END,
  CASE MOD(LEVEL, 3) WHEN 0 THEN 'Seoul' WHEN 1 THEN 'Tokyo' ELSE 'Singapore' END
FROM DUAL CONNECT BY LEVEL <= 20;

COMMIT;

-- Employees (1500) — varied salaries / hire dates / departments
-- Seed first so Data-tab paging shows "Page 1 · Rows 1–500 (more)"
INSERT INTO HR.EMPLOYEES (
  EMPLOYEE_ID, FIRST_NAME, LAST_NAME, EMAIL,
  HIRE_DATE, JOB_ID, SALARY, COMMISSION_PCT,
  MANAGER_ID, DEPARTMENT_ID, STATUS
)
SELECT
  100 + LEVEL,
  'First' || LPAD(LEVEL, 4, '0'),
  CASE MOD(LEVEL, 8)
    WHEN 0 THEN 'Kim'   WHEN 1 THEN 'Lee'   WHEN 2 THEN 'Park'  WHEN 3 THEN 'Choi'
    WHEN 4 THEN 'Jung'  WHEN 5 THEN 'Han'   WHEN 6 THEN 'Yoon'  ELSE        'Shin'
  END,
  'user' || LEVEL || '@example.com',
  TRUNC(SYSDATE - DBMS_RANDOM.VALUE(0, 3650)),
  CASE MOD(LEVEL, 5)
    WHEN 0 THEN 'IT_PROG'
    WHEN 1 THEN 'SA_REP'
    WHEN 2 THEN 'AC_MGR'
    WHEN 3 THEN 'ST_CLERK'
    ELSE        'AD_VP'
  END,
  ROUND(DBMS_RANDOM.VALUE(3000, 25000), 2),
  CASE WHEN MOD(LEVEL, 7) = 0 THEN ROUND(DBMS_RANDOM.VALUE(0.05, 0.40), 2) ELSE NULL END,
  CASE WHEN LEVEL > 50 THEN 100 + MOD(LEVEL, 50) ELSE NULL END,   -- 단순한 manager 트리
  10 + MOD(LEVEL, 20),
  CASE MOD(LEVEL, 20)
    WHEN 0  THEN 'INACTIVE'
    WHEN 1  THEN 'TERMINATED'
    ELSE        'ACTIVE'
  END
FROM DUAL CONNECT BY LEVEL <= 1500;

COMMIT;
