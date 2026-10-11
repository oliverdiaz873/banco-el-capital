package com.bancoelcapital.beneficiaries.web;

import java.time.Instant;
import java.util.UUID;

public record BeneficiaryDetailsResponse(
    UUID beneficiaryId,
    String alias,
    UUID destinationAccountId,
    String destinationCurrency,
    Instant createdAt) {}
