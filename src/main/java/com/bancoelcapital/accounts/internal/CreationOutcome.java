package com.bancoelcapital.accounts.internal;

/** Stored outcome of an account-creation attempt (ADR-04 lifecycle subset). */
public enum CreationOutcome {
  CONFIRMED,
  REJECTED
}
