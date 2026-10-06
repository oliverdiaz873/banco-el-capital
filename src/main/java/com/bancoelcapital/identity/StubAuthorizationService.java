package com.bancoelcapital.identity;

import org.springframework.stereotype.Service;

/**
 * Stub authorization (MVP). Allows creation when the actor is authenticated and either acts for
 * themselves (subject equals holder) or carries the BANK_EMPLOYEE role. Never trust a bare
 * customerId: the service layer always goes through this decision. No JWT/OAuth/Spring Security yet
 * (ADR-09).
 */
@Service
public class StubAuthorizationService implements AuthorizationService {

  @Override
  public AuthorizationDecision decideCreateAccount(
      AuthenticatedActor actor, String holderCustomerId) {
    return decide(actor, holderCustomerId);
  }

  @Override
  public AuthorizationDecision decideCreateCustomer(AuthenticatedActor actor, String customerId) {
    return decide(actor, customerId);
  }

  @Override
  public AuthorizationDecision decideDeposit(AuthenticatedActor actor, String holderCustomerId) {
    return decide(actor, holderCustomerId);
  }

  private AuthorizationDecision decide(AuthenticatedActor actor, String holderCustomerId) {
    if (actor == null || actor.subject() == null || actor.subject().isBlank()) {
      return AuthorizationDecision.deny("unauthenticated actor");
    }
    if (actor.subject().equals(holderCustomerId) || actor.hasRole("BANK_EMPLOYEE")) {
      return AuthorizationDecision.allow();
    }
    return AuthorizationDecision.deny("actor not authorized for holder");
  }
}
