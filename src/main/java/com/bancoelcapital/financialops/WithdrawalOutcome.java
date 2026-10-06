package com.bancoelcapital.financialops;

import java.util.UUID;

/**
 * Queryable withdrawal outcome: operation plus its stored result. Returned for both CONFIRMED and
 * REJECTED records; absence (or hash mismatch) resolves to no result, never to an inferred outcome.
 */
public record WithdrawalOutcome(UUID operationId, WithdrawalOperationOutcome outcome) {}
