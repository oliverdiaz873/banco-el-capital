-- Transfer beneficiary evidence (Financial Operations + Beneficiaries, ADR-21).
-- Intent columns on the existing transfer idempotency namespace plus a dedicated
-- beneficiary-usage table for CONFIRMED beneficiary transfers. Cross-module references stay
-- logical: no FK to beneficiaries, accounts, customers, or financial_operations, so beneficiary
-- deletion never removes transfer evidence and unknown-destination rejections persist evidence
-- for ids that resolve to nothing.
-- New intent columns are nullable for historical compatibility: rows predating V11 keep all five
-- NULL (historical branch); new rows MUST be complete and coherent (new branch). Phase 2 is a
-- non-deployable intermediate: the transfer path writes intent only from Phase 3 on, so rows
-- written between V11 and Phase 3 MUST NOT be read as historical.
-- request_hash stays VARCHAR(64) following the V7 CHAR/VARCHAR compatibility fix.
ALTER TABLE transfer_operation_idempotency
  ADD COLUMN source_account_id UUID NULL,
  ADD COLUMN beneficiary_id UUID NULL,
  ADD COLUMN path VARCHAR(32) NULL,
  ADD COLUMN amount_minor_units BIGINT NULL,
  ADD COLUMN currency VARCHAR(3) NULL,
  ADD CONSTRAINT ck_transfer_idem_historical_or_complete CHECK ((
    source_account_id IS NULL AND beneficiary_id IS NULL AND path IS NULL
      AND amount_minor_units IS NULL AND currency IS NULL
  ) OR (
    source_account_id IS NOT NULL AND path IS NOT NULL
      AND amount_minor_units IS NOT NULL AND currency IS NOT NULL
  )),
  ADD CONSTRAINT ck_transfer_idem_path CHECK (
    path IS NULL OR path IN ('direct', 'beneficiary-transfer')
  ),
  ADD CONSTRAINT ck_transfer_idem_path_coherence CHECK (
    path IS NULL
      OR (path = 'direct' AND beneficiary_id IS NULL)
      OR (path = 'beneficiary-transfer' AND beneficiary_id IS NOT NULL)
  );

CREATE TABLE transfer_beneficiary_usage (
  operation_id UUID PRIMARY KEY,
  beneficiary_id UUID NOT NULL,
  beneficiary_owner VARCHAR(64) NOT NULL,
  beneficiary_alias VARCHAR(64) NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_transfer_beneficiary_usage_beneficiary ON transfer_beneficiary_usage(beneficiary_id);
