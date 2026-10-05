package com.bancoelcapital.identity;

/**
 * Transitional stub, NOT wired as a bean. The productive gateway is JpaIdentityGateway backed by
 * Customers. Kept without @Service so AccountCreationService resolves the real implementation.
 */
public class StubIdentityGateway implements IdentityGateway {

  @Override
  public boolean customerExists(String customerId) {
    return customerId != null && !customerId.isBlank();
  }
}
