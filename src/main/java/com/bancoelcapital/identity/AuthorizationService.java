package com.bancoelcapital.identity;

/** Decides whether an actor MAY request account, customer, or deposit operations. */
public interface AuthorizationService {
  AuthorizationDecision decideCreateAccount(AuthenticatedActor actor, String holderCustomerId);

  AuthorizationDecision decideCreateCustomer(AuthenticatedActor actor, String customerId);

  AuthorizationDecision decideDeposit(AuthenticatedActor actor, String holderCustomerId);
}
