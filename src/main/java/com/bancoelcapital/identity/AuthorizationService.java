package com.bancoelcapital.identity;

/** Decides whether an actor MAY request account or customer creation for a holder. */
public interface AuthorizationService {
  AuthorizationDecision decideCreateAccount(AuthenticatedActor actor, String holderCustomerId);

  AuthorizationDecision decideCreateCustomer(AuthenticatedActor actor, String customerId);
}
