package com.bancoelcapital.identity;

/**
 * Reads customer existence without owning Customers (ADR-01). The stub accepts any non-blank id and
 * MUST be replaced once the Customers module exists.
 */
public interface IdentityGateway {
  boolean customerExists(String customerId);
}
