package com.bancoelcapital.accounts.internal;

/** Stored outcome of a transition attempt (ADR-04 lifecycle subset applied to state, ADR-19). */
public enum TransitionOutcome {
  CONFIRMED,
  REJECTED
}
