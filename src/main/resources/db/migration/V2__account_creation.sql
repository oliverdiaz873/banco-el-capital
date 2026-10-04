-- Account Creation (Feature Build, ADR-03).
-- Minimal support only: accounts, creation idempotency, audit evidence.
-- Single-currency enforcement across accounts is a transfer-time rule, not a creation rule.
CREATE TABLE accounts (
  id UUID PRIMARY KEY,
  holder_customer_id VARCHAR(64) NOT NULL,
  currency CHAR(3) NOT NULL,
  product_code VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE account_creation_idempotency (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  account_id UUID NULL REFERENCES accounts(id),
  request_hash CHAR(64) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_events (
  id UUID PRIMARY KEY,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor VARCHAR(128) NOT NULL,
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  result VARCHAR(16) NOT NULL,
  details VARCHAR(2000) NULL
);
