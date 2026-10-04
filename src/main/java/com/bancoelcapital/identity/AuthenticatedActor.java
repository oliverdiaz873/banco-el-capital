package com.bancoelcapital.identity;

import java.util.Set;

/**
 * Authenticated actor stub (ADR-09). An authenticated user is NOT necessarily a holder; holder
 * relationships live in Accounts. Roles are opaque strings until a real identity provider exists.
 */
public record AuthenticatedActor(String subject, Set<String> roles) {
  public boolean hasRole(String role) {
    return roles != null && roles.contains(role);
  }
}
