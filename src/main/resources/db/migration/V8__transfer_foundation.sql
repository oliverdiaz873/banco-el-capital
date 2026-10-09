-- Transfer foundation (Financial Operations, ADR-18, Option A).
-- Dedicated idempotency namespace, separate from deposit and withdrawal idempotency.
-- Cross-module references stay logical: no FK to accounts or customers.
-- FinancialOperation type TRANSFER reuses the existing financial_operations table with
-- account_id identifying the SOURCE account (Option A, no destination_account_id column
-- and no change to that table here); the destination is reconstructed from the linked
-- CREDIT movement sharing the same operation_id. No migration beyond this table here.
-- request_hash is VARCHAR(64) directly, following the V7 CHAR/VARCHAR compatibility fix.
CREATE TABLE transfer_operation_idempotency (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  operation_id UUID NULL REFERENCES financial_operations(id),
  request_hash VARCHAR(64) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
