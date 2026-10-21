package com.bancoelcapital.financialops.transfer;

import java.util.UUID;

/**
 * Intent to transfer an explicit amount of minor units from a source account in the same currency
 * (ADR-05 operation, ADR-18 Option A). Direct intent references destinationAccountId; beneficiary
 * intent references beneficiaryId instead, with the destination resolved server-side from the
 * owner-scoped saved beneficiary (ADR-21).
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
