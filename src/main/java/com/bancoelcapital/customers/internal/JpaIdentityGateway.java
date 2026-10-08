package com.bancoelcapital.customers.internal;

import org.springframework.stereotype.Service;

import com.bancoelcapital.identity.IdentityGateway;

/**
 * Real identity gateway backed by Customers (ADR-01). Accounts keeps depending on the Identity
 * contract only; it MUST NOT access CustomerRepository directly.
 */
@Service
public class JpaIdentityGateway implements IdentityGateway {

  private final CustomerRepository customers;

  public JpaIdentityGateway(CustomerRepository customers) {
    this.customers = customers;
  }

  @Override
  public boolean customerExists(String customerId) {
    if (customerId == null || customerId.isBlank()) {
      return false;
    }
    return customers.existsById(customerId);
  }
}
