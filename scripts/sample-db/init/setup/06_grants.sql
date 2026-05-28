-- Grant V$* views and ALTER SYSTEM so the HR user can drive the
-- Sessions tool window (Current SQL / Wait History / Stats / Plan)
-- and the Kill Session action against itself for demos.
--
-- WARNING: This is for the local demo container only. Do NOT grant
-- SELECT_CATALOG_ROLE + ALTER SYSTEM to a production user.

ALTER SESSION SET CONTAINER = FREEPDB1;

GRANT SELECT_CATALOG_ROLE TO HR;
GRANT ALTER SYSTEM        TO HR;
