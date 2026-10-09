package com.bancoelcapital.accounts.web;

import java.time.Instant;
import java.util.UUID;

public record AccountDetailsResponse(
    UUID accountId,
    String holderCustomerId,
    String currency,
    String productCode,
    String status,
    Long balanceMinorUnits,
    Instant createdAt) {}
