package com.bancoelcapital.identity;

/**
 * Decides whether an actor MAY request account, customer, deposit, withdrawal, transfer, or
 * lifecycle operations.
 */
public interface AuthorizationService {
  AuthorizationDecision decideCreateAccount(AuthenticatedActor actor, String holderCustomerId);

  AuthorizationDecision decideCreateCustomer(AuthenticatedActor actor, String customerId);

  AuthorizationDecision decideDeposit(AuthenticatedActor actor, String holderCustomerId);

  AuthorizationDecision decideWithdraw(AuthenticatedActor actor, String holderCustomerId);

  AuthorizationDecision decideTransfer(AuthenticatedActor actor, String sourceHolderCustomerId);

  AuthorizationDecision decideTransition(AuthenticatedActor actor, String holderCustomerId);

  AuthorizationDecision decideViewAccount(AuthenticatedActor actor, String holderCustomerId);
}
