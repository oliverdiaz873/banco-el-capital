-- Customer / Identity Foundation (ADR-01/ADR-09).
-- Minimal Customers module: customers plus creation idempotency.
-- accounts.holder_customer_id stays a logical reference: no physical FK.
CREATE TABLE customers (
  customer_id VARCHAR(64) PRIMARY KEY,
  display_name VARCHAR(128) NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by VARCHAR(128) NOT NULL
);

CREATE TABLE customer_creation_idempotency (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  customer_id VARCHAR(64) NULL REFERENCES customers(customer_id),
  request_hash CHAR(64) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
