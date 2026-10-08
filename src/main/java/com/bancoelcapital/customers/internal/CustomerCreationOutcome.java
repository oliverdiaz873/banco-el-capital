package com.bancoelcapital.customers.internal;

/**
 * Stored outcome of a customer-creation attempt (ADR-04 lifecycle subset, mirrored from Accounts).
 */
public enum CustomerCreationOutcome {
  CONFIRMED,
  REJECTED
}
