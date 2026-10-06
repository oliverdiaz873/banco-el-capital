package com.bancoelcapital.accounts;

/**
 * Result of an Accounts-owned debit application. Distinguishes lookup, operability, currency, and
 * funds outcomes without Financial Operation states; REJECTED mapping belongs to the calling
 * withdrawal use case of a later phase.
 */
public record AccountDebitResult(AccountDebitOutcome outcome, Long balanceMinorUnits) {

  public enum AccountDebitOutcome {
    APPLIED,
    ACCOUNT_NOT_FOUND,
    NOT_OPERABLE,
    CURRENCY_MISMATCH,
    INVALID_AMOUNT,
    INSUFFICIENT_FUNDS
  }

  public boolean applied() {
    return outcome == AccountDebitOutcome.APPLIED;
  }
}
