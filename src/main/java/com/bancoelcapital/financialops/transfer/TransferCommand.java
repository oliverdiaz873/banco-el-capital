package com.bancoelcapital.financialops.transfer;

import java.util.UUID;

/**
 * Intent to transfer an explicit amount of minor units from a source account in the same currency
 * (ADR-05 operation, ADR-18 Option A). Direct intent references destinationAccountId; beneficiary
 * intent references beneficiaryId instead (P0: contract and identity only; beneficiary resolution
 * is a later package).
 */
public record TransferCommand(
    UUID sourceAccountId,
    UUID destinationAccountId,
    UUID beneficiaryId,
    Long amountMinorUnits,
    String currency) {

  public TransferCommand(
      UUID sourceAccountId, UUID destinationAccountId, Long amountMinorUnits, String currency) {
    this(sourceAccountId, destinationAccountId, null, amountMinorUnits, currency);
  }
}
