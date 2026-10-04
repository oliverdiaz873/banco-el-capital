package com.bancoelcapital.accounts;

/** Stored outcome of an account-creation attempt (ADR-04 lifecycle subset). */
public enum CreationOutcome {
  CONFIRMED,
  REJECTED
}
