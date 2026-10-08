package com.bancoelcapital.financialops.withdrawal;

import java.util.UUID;

/** Intent to withdraw an explicit amount of minor units from an account (ADR-16 operation). */
public record WithdrawalCommand(UUID accountId, Long amountMinorUnits, String currency) {}
