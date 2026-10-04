package com.bancoelcapital.identity;

/** Decides whether an actor MAY request account creation for a holder. */
public interface AuthorizationService {
  AuthorizationDecision decideCreateAccount(AuthenticatedActor actor, String holderCustomerId);
}
