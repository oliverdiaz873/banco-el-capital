-- CHAR/VARCHAR compatibility (Phase 14 diagnosis).
-- JPA maps Java String with length to VARCHAR(n), while V2/V4/V5/V6 declared CHAR(n).
-- PostgreSQL exposes CHAR(n) as bpchar, so ddl-auto=validate fails; H2 never detected it.
-- This additive migration converts the 7 affected columns to VARCHAR, preserving nullability,
-- constraints, indexes, and values (stored hashes and ISO currencies already use exact lengths).
-- V1-V6 stay untouched.
ALTER TABLE account_creation_idempotency
  ALTER COLUMN request_hash TYPE VARCHAR(64);

ALTER TABLE customer_creation_idempotency
  ALTER COLUMN request_hash TYPE VARCHAR(64);

ALTER TABLE deposit_operation_idempotency
  ALTER COLUMN request_hash TYPE VARCHAR(64);

ALTER TABLE withdrawal_operation_idempotency
  ALTER COLUMN request_hash TYPE VARCHAR(64);

ALTER TABLE accounts
  ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE financial_operations
  ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE movements
  ALTER COLUMN currency TYPE VARCHAR(3);
