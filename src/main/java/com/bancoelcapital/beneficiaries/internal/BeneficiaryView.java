package com.bancoelcapital.beneficiaries.internal;

import java.time.Instant;
import java.util.UUID;

/** Minimal observable beneficiary state for owner-scoped reads (ADR-20). No financial data. */
public record BeneficiaryView(
    UUID beneficiaryId,
    String alias,
    UUID destinationAccountId,
    String destinationCurrency,
    Instant createdAt) {}
