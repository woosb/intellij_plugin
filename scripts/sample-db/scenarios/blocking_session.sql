-- Create a blocked-session scenario for the Sessions tool window screenshot.
-- Run the two halves in TWO separate DataGrip consoles, both connected as HR.
--
-- Console A (blocker)  → leaves a row locked, does NOT commit
-- Console B (waiter)   → blocks on the same row
--
-- After capturing the screenshot, run the cleanup section at the bottom.

-- ─────────────────────────────────────────────────────────────────
-- CONSOLE A (Blocker) — run this once and leave it open
-- ─────────────────────────────────────────────────────────────────
UPDATE HR.EMPLOYEES
   SET SALARY = SALARY            -- no-op update, just to take a row lock
 WHERE EMPLOYEE_ID = 100;
-- DO NOT COMMIT. Leave this console idle while you take the screenshot.


-- ─────────────────────────────────────────────────────────────────
-- CONSOLE B (Waiter) — run this in a second console, it will hang
-- ─────────────────────────────────────────────────────────────────
SELECT * FROM HR.EMPLOYEES WHERE EMPLOYEE_ID = 100 FOR UPDATE;
-- ↑ This statement will sit and wait for Console A.
-- → Now open the Oracle Sessions tool window:
--      Console B's row appears with BLOCKED BY = Console A's SID (red row).


-- ─────────────────────────────────────────────────────────────────
-- CLEANUP — run in Console A when you are done
-- ─────────────────────────────────────────────────────────────────
ROLLBACK;
