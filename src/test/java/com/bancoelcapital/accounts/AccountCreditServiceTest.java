package com.bancoelcapital.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountCreditServiceTest {

  @Mock AccountRepository accounts;

  AccountCreditService service;

  UUID accountId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    service = new AccountCreditService(accounts);
  }

  @Test
  void appliesCreditToActiveAccountWithMatchingCurrency() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(new Account(accountId, "holder-1", "DOP", "BASIC")));

    AccountCreditResult result =
        service.applyCredit(new AccountCreditCommand(accountId, 10_00L, "DOP"));

    assertThat(result.applied()).isTrue();
    assertThat(result.balanceMinorUnits()).isEqualTo(10_00L);
    verify(accounts).save(any(Account.class));
  }

  @Test
  void accumulatesOnExistingBalance() {
    Account account = new Account(accountId, "holder-1", "DOP", "BASIC");
    account.applyCredit(5_00L);
    when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

    AccountCreditResult result =
        service.applyCredit(new AccountCreditCommand(accountId, 10_00L, "DOP"));

    assertThat(result.balanceMinorUnits()).isEqualTo(15_00L);
  }

  @Test
  void reportsAccountNotFound() {
    when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.empty());

    AccountCreditResult result =
        service.applyCredit(new AccountCreditCommand(accountId, 10_00L, "DOP"));

    assertThat(result.outcome())
        .isEqualTo(AccountCreditResult.AccountCreditOutcome.ACCOUNT_NOT_FOUND);
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsInvalidAmounts() {
    for (Long amount : new Long[] {null, 0L, -1L}) {
      AccountCreditResult result =
          service.applyCredit(new AccountCreditCommand(accountId, amount, "DOP"));

      assertThat(result.outcome())
          .isEqualTo(AccountCreditResult.AccountCreditOutcome.INVALID_AMOUNT);
    }
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsCurrencyMismatch() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(new Account(accountId, "holder-1", "DOP", "BASIC")));

    AccountCreditResult result =
        service.applyCredit(new AccountCreditCommand(accountId, 10_00L, "USD"));

    assertThat(result.outcome())
        .isEqualTo(AccountCreditResult.AccountCreditOutcome.CURRENCY_MISMATCH);
    verify(accounts, never()).save(any());
  }
}
