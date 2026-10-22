-- Direct-transfer destination evidence (ADR-21, R-2, Phase 4.3 closure).
-- The client-supplied destination of a DIRECT transfer is persisted for CONFIRMED and REJECTED
-- outcomes so replay can compare the exact intent without relying on the hash alone. Beneficiary
-- transfers keep this column NULL: their destination is server-resolved execution evidence and
-- remains reconstructable from the linked CREDIT movement (ADR-18 Option A). No FK is added:
-- cross-module references stay logical, and an unknown destination rejection must still persist
-- the attempted id.
--
-- Scope check: the column may only be populated on the direct path. The column is new and all
-- existing rows have NULL, so this constraint can be validated without touching historical data.
--
-- Direct-destination check: NOT VALID. Existing direct rows predate this column and have no
-- destination; validating the check would require a prohibited backfill. PostgreSQL enforces a
-- NOT VALID CHECK for every new INSERT and UPDATE, so new direct rows must carry a destination.
-- NOT VALID does NOT classify or exempt individual historical rows: it only skips the initial
-- validation of rows that already existed when the constraint was added. Legacy direct rows
-- therefore remain readable and are treated as hash-only by replay.
--
-- Known residual: a NEW row that leaves every intent column NULL still satisfies the V11
-- historical branch. V13 deliberately does not add the broader completeness constraint C, a
-- trigger, or a version marker; that pre-existing gap is recorded in docs/risk-register.md.
ALTER TABLE transfer_operation_idempotency
  ADD COLUMN destination_account_id UUID NULL;

ALTER TABLE transfer_operation_idempotency
  ADD CONSTRAINT ck_transfer_idem_destination_scope
  CHECK (destination_account_id IS NULL OR path IS NOT DISTINCT FROM 'direct');

ALTER TABLE transfer_operation_idempotency
  ADD CONSTRAINT ck_transfer_idem_direct_destination
  CHECK (path IS DISTINCT FROM 'direct' OR destination_account_id IS NOT NULL)
  NOT VALID;
