package com.bancoelcapital.accounts.api;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.accounts.internal.Account;
import com.bancoelcapital.accounts.internal.AccountRepository;

/**
 * Accounts-owned balance debit contract (ADR-16). Verifies existence, operability via isOperable,
 * currency compatibility, amount validity, and sufficient funds as aggregate protection before
 * mutating stored balance. The account row is write-locked so the definitive funds check and the
 * debit apply atomically inside the calling withdrawal's single local transaction, on H2 and
 * PostgreSQL alike. Transactional with REQUIRED propagation so the calling Withdrawal use case can
 * coordinate it inside its own single local transaction.
 */
@Service
public class AccountDebitService {

  private final AccountRepository accounts;

  public AccountDebitService(AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Transactional
  public AccountDebitResult applyDebit(AccountDebitCommand command) {
    if (command.amountMinorUnits() == null || command.amountMinorUnits() <= 0) {
      return new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.INVALID_AMOUNT, null);
    }
    Optional<Account> stored = accounts.findByIdForUpdate(command.accountId());
    if (stored.isEmpty()) {
      return new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.ACCOUNT_NOT_FOUND, null);
    }
    Account account = stored.get();
    if (!account.isOperable()) {
      return new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.NOT_OPERABLE, null);
    }
    if (command.currency() == null || !command.currency().equals(account.getCurrency())) {
      return new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.CURRENCY_MISMATCH, null);
    }
    if (command.amountMinorUnits() > account.getBalanceMinorUnits()) {
      return new AccountDebitResult(
          AccountDebitResult.AccountDebitOutcome.INSUFFICIENT_FUNDS, null);
    }
    account.applyDebit(command.amountMinorUnits());
    accounts.save(account);
    return new AccountDebitResult(
        AccountDebitResult.AccountDebitOutcome.APPLIED, account.getBalanceMinorUnits());
  }
}
