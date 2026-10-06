package com.bancoelcapital.accounts;

/**
 * Result of an Accounts-owned credit application. Distinguishes lookup, operability, and currency
 * outcomes without Financial Operation states; REJECTED mapping belongs to the calling Deposit use
 * case.
 */
public record AccountCreditResult(AccountCreditOutcome outcome, Long balanceMinorUnits) {

  public enum AccountCreditOutcome {
    APPLIED,
    ACCOUNT_NOT_FOUND,
    NOT_OPERABLE,
    CURRENCY_MISMATCH,
    INVALID_AMOUNT
  }

  public boolean applied() {
    return outcome == AccountCreditOutcome.APPLIED;
  }
}
