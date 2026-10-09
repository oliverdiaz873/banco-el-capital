-- Account lifecycle foundation (Accounts, ADR-19).
-- Dedicated idempotency namespace for block/unblock/close transitions.
-- The account reference stays logical (no FK to accounts): unknown-account rejections persist
-- evidence for ids that resolve to nothing, and transitions never depend on a physical link.
-- request_hash is VARCHAR(64) directly, following the V7 CHAR/VARCHAR compatibility fix.
-- No change to the accounts table here: the status column already exists.
CREATE TABLE account_transition_idempotency (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  account_id UUID NULL,
  request_hash VARCHAR(64) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
