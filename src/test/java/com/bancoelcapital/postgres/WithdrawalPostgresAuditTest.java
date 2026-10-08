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
 * Audit behavior against real PostgreSQL: confirmed, rejected and conflict outcomes each persist
 * exactly one event; replays record nothing new.
 */
class WithdrawalPostgresAuditTest extends AbstractPostgresTest {

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
    String holder = "holder-pgau-" + tag;
    actor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, actor);
    var created =
        accounts.create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, actor);
    accountId = created.accountId();
    deposits.deposit(new DepositCommand(accountId, 100_00L, "DOP"), "f-" + tag, actor);
    jdbc.execute("delete from audit_events");
  }

  @Test
  void confirmedWithdrawalAuditsOnce() {
    WithdrawalResult result =
        withdrawals.withdraw(new WithdrawalCommand(accountId, 40_00L, "DOP"), "w-ok-" + tag, actor);

    assertThat(result.created()).isTrue();
    assertThat(auditCount("WITHDRAWAL_CONFIRMED")).isEqualTo(1);
  }

  @Test
  void rejectedWithdrawalAuditsRejectionOnly() {
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    new WithdrawalCommand(accountId, 999_00L, "DOP"), "w-rej-" + tag, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.REJECTED);

    assertThat(auditCount("WITHDRAWAL_REJECTED")).isEqualTo(1);
    assertThat(auditCount("WITHDRAWAL_CONFIRMED")).isZero();
  }

  @Test
  void conflictAuditsOnceWithoutNewEffects() {
    String key = "w-cfl-" + tag;
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(new WithdrawalCommand(accountId, 999_00L, "DOP"), key, actor))
        .isInstanceOf(WithdrawalOperationException.class);
    assertThatThrownBy(
            () -> withdrawals.withdraw(new WithdrawalCommand(accountId, 10_00L, "DOP"), key, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.CONFLICT);

    assertThat(auditCount("WITHDRAWAL_CONFLICT")).isEqualTo(1);
    assertThat(auditCount("WITHDRAWAL_CONFIRMED")).isZero();
  }

  @Test
  void replaysRecordNoSecondAudit() {
    String confirmedKey = "w-rp-" + tag;
    withdrawals.withdraw(new WithdrawalCommand(accountId, 40_00L, "DOP"), confirmedKey, actor);
    withdrawals.withdraw(new WithdrawalCommand(accountId, 40_00L, "DOP"), confirmedKey, actor);

    String rejectedKey = "w-rpr-" + tag;
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    new WithdrawalCommand(accountId, 999_00L, "DOP"), rejectedKey, actor))
        .isInstanceOf(WithdrawalOperationException.class);
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    new WithdrawalCommand(accountId, 999_00L, "DOP"), rejectedKey, actor))
        .isInstanceOf(WithdrawalOperationException.class);

    assertThat(auditCount("WITHDRAWAL_CONFIRMED")).isEqualTo(1);
    assertThat(auditCount("WITHDRAWAL_REJECTED")).isEqualTo(1);
  }

  private int auditCount(String action) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from audit_events where action = ?", Integer.class, action);
    return count == null ? 0 : count;
  }
}
