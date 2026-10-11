-- Beneficiary foundation (Beneficiaries, ADR-20).
-- Owner-scoped saved internal-account references plus a dedicated creation idempotency
-- namespace. Cross-module references stay logical: no FK to accounts or customers, so
-- unknown-destination rejections persist evidence for ids that resolve to nothing and
-- physical deletion never depends on a physical link.
-- Uniqueness is enforced on (owner, destination) only: no unique alias constraint.
-- request_hash is VARCHAR(64) directly, following the V7 CHAR/VARCHAR compatibility fix.
CREATE TABLE beneficiaries (
  id UUID PRIMARY KEY,
  owner_customer_id VARCHAR(64) NOT NULL,
  destination_account_id UUID NOT NULL,
  alias VARCHAR(64) NOT NULL,
  destination_currency VARCHAR(3) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_beneficiary_owner_destination UNIQUE (owner_customer_id, destination_account_id)
);

CREATE INDEX ix_beneficiaries_owner ON beneficiaries(owner_customer_id);

CREATE TABLE beneficiary_creation_idempotency (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  beneficiary_id UUID NULL,
  request_hash VARCHAR(64) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  owner_customer_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
