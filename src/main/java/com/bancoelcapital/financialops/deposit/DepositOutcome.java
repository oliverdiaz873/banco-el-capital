package com.bancoelcapital.financialops.deposit;

import java.util.UUID;

/**
 * Queryable deposit outcome: operation plus its stored result. Returned for both CONFIRMED and
 * REJECTED records; absence (or hash mismatch) resolves to no result, never to an inferred outcome.
 */
public record DepositOutcome(UUID operationId, DepositOperationOutcome outcome) {}
