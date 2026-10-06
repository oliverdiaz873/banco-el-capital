package com.bancoelcapital.accounts;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts-owned balance application contract (ADR-15). Verifies existence, operability via
 * isOperable, currency compatibility, and amount validity before mutating stored balance. The
 * account row is write-locked so concurrent credits serialize instead of losing updates.
 * Transactional with REQUIRED propagation so a calling Deposit use case can coordinate it inside
 * its own single local transaction.
 */
@Service
public class AccountCreditService {

  private final AccountRepository accounts;

  public AccountCreditService(AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Transactional
  public AccountCreditResult applyCredit(AccountCreditCommand command) {
    if (command.amountMinorUnits() == null || command.amountMinorUnits() <= 0) {
      return new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.INVALID_AMOUNT, null);
    }
    Optional<Account> stored = accounts.findByIdForUpdate(command.accountId());
    if (stored.isEmpty()) {
      return new AccountCreditResult(
          AccountCreditResult.AccountCreditOutcome.ACCOUNT_NOT_FOUND, null);
    }
    Account account = stored.get();
    if (!account.isOperable()) {
      return new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.NOT_OPERABLE, null);
    }
    if (command.currency() == null || !command.currency().equals(account.getCurrency())) {
      return new AccountCreditResult(
          AccountCreditResult.AccountCreditOutcome.CURRENCY_MISMATCH, null);
    }
    account.applyCredit(command.amountMinorUnits());
    accounts.save(account);
    return new AccountCreditResult(
        AccountCreditResult.AccountCreditOutcome.APPLIED, account.getBalanceMinorUnits());
  }
}
