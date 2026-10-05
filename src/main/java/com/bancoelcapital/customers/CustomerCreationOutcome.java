package com.bancoelcapital.customers;

/**
 * Stored outcome of a customer-creation attempt (ADR-04 lifecycle subset, mirrored from Accounts).
 */
public enum CustomerCreationOutcome {
  CONFIRMED,
  REJECTED
}
