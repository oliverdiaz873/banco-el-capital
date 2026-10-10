package com.bancoelcapital.accounts.internal;

import java.time.Instant;
import java.util.UUID;

/** Minimal observable account state for lifecycle reads (ADR-19). No financial history here. */
public record AccountView(
    UUID accountId,
    String holderCustomerId,
    String currency,
    String productCode,
    AccountStatus status,
    Long balanceMinorUnits,
    Instant createdAt) {}
