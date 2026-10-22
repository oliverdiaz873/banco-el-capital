-- Transfer idempotency integrity (ADR-06, ADR-21, H-06).
-- A CONFIRMED transfer must reference the financial operation it confirms. REJECTED rows may keep
-- a null operation_id where the existing contract allows it. This migration is additive: it does
-- not update, delete, re-link, or reinterpret any existing row.
--
-- Preflight: fail explicitly before adding the constraint if historical data already violates it.
-- The exception aborts the migration inside Flyway's transactional DDL; no automatic repair is
-- attempted. An operator must review the reported rows and decide their disposition manually.
DO $v12_preflight$
DECLARE
  invalid_rows BIGINT;
BEGIN
  SELECT count(*)
    INTO invalid_rows
    FROM transfer_operation_idempotency
   WHERE outcome = 'CONFIRMED'
     AND operation_id IS NULL;

  IF invalid_rows > 0 THEN
    RAISE EXCEPTION
      'V12 preflight failed: % CONFIRMED transfer idempotency row(s) have NULL operation_id',
      invalid_rows
      USING HINT = 'Manual review is required. V12 does not repair, delete, re-link, or change the outcome of historical rows.';
  END IF;
END
$v12_preflight$;

ALTER TABLE transfer_operation_idempotency
  ADD CONSTRAINT ck_transfer_idem_confirmed_operation
  CHECK (outcome <> 'CONFIRMED' OR operation_id IS NOT NULL);
