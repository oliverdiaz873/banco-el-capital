package com.bancoelcapital.financialops.transfer;

/** Stored outcome of a transfer-operation attempt (ADR-04 lifecycle subset, ADR-18). */
public enum TransferOperationOutcome {
  CONFIRMED,
  REJECTED
}
