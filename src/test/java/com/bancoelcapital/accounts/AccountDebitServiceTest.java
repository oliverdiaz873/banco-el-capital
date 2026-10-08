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

import com.bancoelcapital.accounts.api.AccountDebitCommand;
import com.bancoelcapital.accounts.api.AccountDebitResult;
import com.bancoelcapital.accounts.api.AccountDebitService;
import com.bancoelcapital.accounts.internal.Account;
import com.bancoelcapital.accounts.internal.AccountRepository;

@ExtendWith(MockitoExtension.class)
class AccountDebitServiceTest {

  @Mock AccountRepository accounts;

  AccountDebitService service;

  UUID accountId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    service = new AccountDebitService(accounts);
  }

  @Test
  void appliesValidDebit() {
    Account account = new Account(accountId, "holder-1", "DOP", "BASIC");
    account.applyCredit(10_00L);
    when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

    AccountDebitResult result =
        service.applyDebit(new AccountDebitCommand(accountId, 3_00L, "DOP"));

    assertThat(result.applied()).isTrue();
    assertThat(result.balanceMinorUnits()).isEqualTo(7_00L);
    verify(accounts).save(any(Account.class));
  }

  @Test
  void allowsExactBalanceDebitToZero() {
    Account account = new Account(accountId, "holder-1", "DOP", "BASIC");
    account.applyCredit(10_00L);
    when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

    AccountDebitResult result =
        service.applyDebit(new AccountDebitCommand(accountId, 10_00L, "DOP"));

    assertThat(result.applied()).isTrue();
    assertThat(result.balanceMinorUnits()).isZero();
  }

  @Test
  void rejectsInsufficientFundsWithoutMutatingBalance() {
    Account account = new Account(accountId, "holder-1", "DOP", "BASIC");
    account.applyCredit(10_00L);
    when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

    AccountDebitResult result =
        service.applyDebit(new AccountDebitCommand(accountId, 10_01L, "DOP"));

    assertThat(result.outcome())
        .isEqualTo(AccountDebitResult.AccountDebitOutcome.INSUFFICIENT_FUNDS);
    assertThat(account.getBalanceMinorUnits()).isEqualTo(10_00L);
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsPositiveDebitOnZeroBalance() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(new Account(accountId, "holder-1", "DOP", "BASIC")));

    AccountDebitResult result = service.applyDebit(new AccountDebitCommand(accountId, 1L, "DOP"));

    assertThat(result.outcome())
        .isEqualTo(AccountDebitResult.AccountDebitOutcome.INSUFFICIENT_FUNDS);
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsInvalidAmounts() {
    for (Long amount : new Long[] {null, 0L, -1L}) {
      AccountDebitResult result =
          service.applyDebit(new AccountDebitCommand(accountId, amount, "DOP"));

      assertThat(result.outcome()).isEqualTo(AccountDebitResult.AccountDebitOutcome.INVALID_AMOUNT);
    }
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsHugeAmountAsInsufficientRatherThanArithmeticFailure() {
    Account account = new Account(accountId, "holder-1", "DOP", "BASIC");
    account.applyCredit(10_00L);
    when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

    AccountDebitResult result =
        service.applyDebit(new AccountDebitCommand(accountId, Long.MAX_VALUE, "DOP"));

    assertThat(result.outcome())
        .isEqualTo(AccountDebitResult.AccountDebitOutcome.INSUFFICIENT_FUNDS);
    assertThat(account.getBalanceMinorUnits()).isEqualTo(10_00L);
    verify(accounts, never()).save(any());
  }

  @Test
  void reportsAccountNotFound() {
    when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.empty());

    AccountDebitResult result =
        service.applyDebit(new AccountDebitCommand(accountId, 1_00L, "DOP"));

    assertThat(result.outcome())
        .isEqualTo(AccountDebitResult.AccountDebitOutcome.ACCOUNT_NOT_FOUND);
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsCurrencyMismatch() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(new Account(accountId, "holder-1", "DOP", "BASIC")));

    AccountDebitResult result =
        service.applyDebit(new AccountDebitCommand(accountId, 1_00L, "USD"));

    assertThat(result.outcome())
        .isEqualTo(AccountDebitResult.AccountDebitOutcome.CURRENCY_MISMATCH);
    verify(accounts, never()).save(any());
  }
}
