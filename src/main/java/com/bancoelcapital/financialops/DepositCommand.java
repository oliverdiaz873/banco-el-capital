package com.bancoelcapital.financialops;

import java.util.UUID;

/** Intent to deposit an explicit amount of minor units into an account (ADR-02 operation). */
public record DepositCommand(UUID accountId, Long amountMinorUnits, String currency) {}
