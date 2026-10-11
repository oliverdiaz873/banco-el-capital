package com.bancoelcapital.beneficiaries.internal;

/** Creation outcomes persisted in the beneficiary creation idempotency namespace (ADR-20). */
public enum BeneficiaryCreationOutcome {
  CONFIRMED,
  REJECTED
}
