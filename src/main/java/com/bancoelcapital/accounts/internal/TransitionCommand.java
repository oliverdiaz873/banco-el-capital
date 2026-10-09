package com.bancoelcapital.accounts.internal;

import java.util.UUID;

/** Intent to apply one lifecycle transition to an account (ADR-19). */
public record TransitionCommand(UUID accountId, AccountTransition transition) {}
