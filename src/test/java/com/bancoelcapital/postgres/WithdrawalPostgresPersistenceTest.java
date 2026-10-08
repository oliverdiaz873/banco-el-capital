package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.financialops.withdrawal.WithdrawalCommand;
import com.bancoelcapital.financialops.withdrawal.WithdrawalOperationException;
import com.bancoelcapital.financialops.withdrawal.WithdrawalResult;
import com.bancoelcapital.financialops.withdrawal.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Withdrawal persistence against real PostgreSQL: confirmed persists operation, DEBIT movement,
 * balance, idempotency and audit in one transaction; rejected persists operation, idempotency and
 * audit with no movement and no balance change.
 */
class WithdrawalPostgresPersistenceTest extends AbstractPostgresTest {

  @Autowired WithdrawalService withdrawals;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor actor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-pg-" + tag;
    actor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, actor);
    var created =
        accounts.create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, actor);
    accountId = created.accountId();
    deposits.deposit(new DepositCommand(accountId, 100_00L, "DOP"), "f-" + tag, actor);
  }

  @Test
  void confirmedWithdrawalPersistsAllEffectsAtomically() {
    WithdrawalResult result =
        withdrawals.withdraw(new WithdrawalCommand(accountId, 40_00L, "DOP"), "w-ok-" + tag, actor);

    assertThat(result.created()).isTrue();
    assertThat(
            count(
                "select count(*) from financial_operations where id = ? and status = 'CONFIRMED'",
                result.operationId()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from movements where operation_id = ? and direction = 'DEBIT'",
                result.operationId()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from withdrawal_operation_idempotency where idempotency_key = ?"
                    + " and outcome = 'CONFIRMED'",
                "w-ok-" + tag))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'WITHDRAWAL_CONFIRMED' and"
                    + " resource_id = ?",
                result.operationId().toString()))
        .isEqualTo(1);
    assertThat(balance()).isEqualTo(60_00L);
  }

  @Test
  void rejectedWithdrawalPersistsEvidenceWithoutFinancialEffect() {
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    new WithdrawalCommand(accountId, 999_00L, "DOP"), "w-rej-" + tag, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.REJECTED);

    assertThat(
            count(
                "select count(*) from financial_operations where account_id = ?"
                    + " and operation_type = 'WITHDRAWAL' and status = 'REJECTED'",
                accountId))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from withdrawal_operation_idempotency where idempotency_key = ?"
                    + " and outcome = 'REJECTED'",
                "w-rej-" + tag))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from movements where account_id = ? and direction = 'DEBIT'",
                accountId))
        .isZero();
    assertThat(count("select count(*) from audit_events where action = 'WITHDRAWAL_REJECTED'"))
        .isGreaterThanOrEqualTo(1);
    assertThat(balance()).isEqualTo(100_00L);
  }

  @Test
  void exactBalanceWithdrawalLeavesZero() {
    WithdrawalResult result =
        withdrawals.withdraw(
            new WithdrawalCommand(accountId, 100_00L, "DOP"), "w-exact-" + tag, actor);

    assertThat(result.created()).isTrue();
    assertThat(balance()).isZero();
  }

  @Test
  void currencyMismatchIsRejectedWithoutEffect() {
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    new WithdrawalCommand(accountId, 10_00L, "USD"), "w-cur-" + tag, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.REJECTED);

    assertThat(count("select count(*) from movements where account_id = ?", accountId))
        .isEqualTo(1);
    assertThat(balance()).isEqualTo(100_00L);
  }

  private int count(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }

  private Long balance() {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
