package com.bancoelcapital.accounts.internal;

import java.util.UUID;

/**
 * Determinable transition result: the account, its resulting status, and whether this attempt
 * changed the state (as opposed to a state-convergence no-op or a replay).
 */
public record TransitionResult(UUID accountId, AccountStatus status, boolean changed) {}
