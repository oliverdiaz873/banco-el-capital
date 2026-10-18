package com.bancoelcapital.financialops.history;

import java.time.Instant;
import java.util.UUID;

import com.bancoelcapital.financialops.core.FinancialOperationType;
import com.bancoelcapital.financialops.core.MovementDirection;

/**
 * Flat read projection of one confirmed account movement (ADR-22). Carries no account identifier of
 * any kind: neither the queried account nor the sibling leg of a transfer. The movement id is the
 * keyset tie-breaker, never an exposure of another account.
 */
public record MovementHistoryEntry(
    UUID movementId,
    UUID operationId,
    FinancialOperationType operationType,
    MovementDirection direction,
    Long amountMinorUnits,
    String currency,
    Instant createdAt) {}
