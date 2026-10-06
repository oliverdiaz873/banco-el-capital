-- Withdrawal foundation (Financial Operations, ADR-16).
-- Dedicated idempotency namespace, separate from deposit idempotency.
-- Cross-module references stay logical: no FK to accounts or customers.
-- FinancialOperation type WITHDRAWAL and Movement direction DEBIT reuse the existing
-- financial_operations and movements tables; no changes to those tables here.
CREATE TABLE withdrawal_operation_idempotency (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  operation_id UUID NULL REFERENCES financial_operations(id),
  request_hash CHAR(64) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
