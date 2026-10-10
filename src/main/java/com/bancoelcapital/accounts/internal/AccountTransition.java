package com.bancoelcapital.accounts.internal;

/**
 * Requested account-lifecycle transition (ADR-19). State changes only; transitions move no money,
 * create no movement, and need no funds check. Close additionally requires a zero stored balance,
 * verified by the service under the row write lock.
 */
public enum AccountTransition {
  BLOCK,
  UNBLOCK,
  CLOSE
}
