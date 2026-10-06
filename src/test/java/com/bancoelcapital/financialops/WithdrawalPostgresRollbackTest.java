package com.bancoelcapital.financialops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bancoelcapital.accounts.AccountCreationCommand;
import com.bancoelcapital.accounts.AccountCreationService;
import com.bancoelcapital.customers.CustomerCreationCommand;
import com.bancoelcapital.customers.CustomerCreationService;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.postgres.AbstractPostgresTest;

/**
 * Real PostgreSQL rollback proof: a technical failure mid-confirmation leaves no operation, no
 * movement, no idempotency, no balance change and no false CONFIRMED audit behind.
 */
class WithdrawalPostgresRollbackTest extends AbstractPostgresTest {

  @Autowired WithdrawalService withdrawals;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @MockitoBean MovementRepository movements;

  UUID accountId;
  AuthenticatedActor actor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-pgrb-" + tag;
    actor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, actor);
    var created =
        accounts.create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, actor);
    accountId = created.accountId();
    deposits.deposit(new DepositCommand(accountId, 100_00L, "DOP"), "f-" + tag, actor);
    jdbc.execute("delete from audit_events");
  }

  @Test
  void technicalFailureRollsBackEveryWithdrawalEffectOnPostgres() {
    doThrow(new RuntimeException("boom")).when(movements).save(any(Movement.class));

    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    new WithdrawalCommand(accountId, 40_00L, "DOP"), "w-boom-" + tag, actor))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(
            count(
                "select count(*) from financial_operations where account_id = ?"
                    + " and operation_type = 'WITHDRAWAL'",
                accountId))
        .isZero();
    assertThat(
            count(
                "select count(*) from withdrawal_operation_idempotency where idempotency_key = ?",
                "w-boom-" + tag))
        .isZero();
    assertThat(count("select count(*) from audit_events where action = 'WITHDRAWAL_CONFIRMED'"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select balance_minor_units from accounts where id = ?", Long.class, accountId))
        .isEqualTo(100_00L);
  }

  private int count(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }
}
