-- Query authorization support: the idempotency record carries the holder so the
-- query endpoint can reuse the creation authorization rule (ADR-09 stub).
-- No backfill needed: no production data exists in Setup/Feature phase.
ALTER TABLE account_creation_idempotency
  ADD COLUMN holder_customer_id VARCHAR(64);
