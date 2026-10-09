package com.bancoelcapital.financialops.transfer;

import java.util.UUID;

/**
 * Intent to transfer an explicit amount of minor units from a source account to a distinct
 * destination account in the same currency (ADR-05 operation, ADR-18 Option A).
 */
public record TransferCommand(
    UUID sourceAccountId, UUID destinationAccountId, Long amountMinorUnits, String currency) {}
