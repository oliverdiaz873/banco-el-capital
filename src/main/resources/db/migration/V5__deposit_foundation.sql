-- Deposit foundation (Financial Operations, ADR-02/ADR-15).
-- Hybrid representation: immutable movements as financial effects plus stored
-- observable balance on accounts. Cross-module references stay logical: no FK
-- between financial operations tables and accounts or customers.
ALTER TABLE accounts
  ADD COLUMN balance_minor_units BIGINT NOT NULL DEFAULT 0;

CREATE TABLE financial_operations (
  id UUID PRIMARY KEY,
  operation_type VARCHAR(32) NOT NULL,
  actor VARCHAR(128) NOT NULL,
  account_id UUID NOT NULL,
  amount_minor_units BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE movements (
  id UUID PRIMARY KEY,
  operation_id UUID NOT NULL REFERENCES financial_operations(id),
  account_id UUID NOT NULL,
  direction VARCHAR(6) NOT NULL,
  amount_minor_units BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE deposit_operation_idempotency (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  operation_id UUID NULL REFERENCES financial_operations(id),
  request_hash CHAR(64) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
