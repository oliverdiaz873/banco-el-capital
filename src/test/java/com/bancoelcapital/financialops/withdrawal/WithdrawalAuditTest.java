package com.bancoelcapital.financialops.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Transactional audit behavior for withdrawals on H2 (PG semantics deferred to Testcontainers when
 * Docker exists). Proves CONFIRMED audit commits with its effects, REJECTED and CONFLICT audits
 * survive their own commits, replays record nothing new, and no balance or other sensitive data
 * leaks into audit details.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:withdrawal-audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class WithdrawalAuditTest {

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
    // Fresh holder and account per test: the Spring context (and H2 mem db) is shared across
    // test methods in the class, so fixed ids would accumulate effects from previous tests.
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-waudit-" + tag;
    actor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, actor);
    var created =
        accounts.create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, actor);
    accountId = created.accountId();
    deposits.deposit(new DepositCommand(accountId, 100_00L, "DOP"), "f-" + tag, actor);
    // Audit rows are global (shared Spring context and H2 mem db across test methods), so reset
    // them per test; each test then asserts exactly the events its own keys produced.
    jdbc.execute("delete from audit_events");
  }

  private int auditCount(String action) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from audit_events where action = ?", Integer.class, action);
    return count == null ? 0 : count;
  }

  @Test
  void confirmedWithdrawalPersistsExactlyOneConfirmedAudit() {
    WithdrawalResult result =
        withdrawals.withdraw(new WithdrawalCommand(accountId, 40_00L, "DOP"), "w-ok-" + tag, actor);

    assertThat(result.created()).isTrue();
    assertThat(auditCount("WITHDRAWAL_CONFIRMED")).isEqualTo(1);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select resource_id, result, details from audit_events"
                + " where action = 'WITHDRAWAL_CONFIRMED'");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("resource_id")).isEqualTo(result.operationId().toString());
    assertThat(rows.get(0).get("result")).isEqualTo("CONFIRMED");
    assertThat(rows.get(0).get("details").toString()).doesNotContain("balance");
  }

  @Test
  void rejectedWithdrawalPersistsRejectedAuditAfterCommit() {
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
    Integer idem =
        jdbc.queryForObject(
            "select count(*) from withdrawal_operation_idempotency"
                + " where idempotency_key = ? and outcome = 'REJECTED'",
            Integer.class,
            "w-rej-" + tag);
    assertThat(idem).isEqualTo(1);
  }

  @Test
  void conflictPersistsConflictAuditWithoutNewEffects() {
    String key = "w-cfl-" + tag;
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(new WithdrawalCommand(accountId, 999_00L, "DOP"), key, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.REJECTED);
    assertThatThrownBy(
            () -> withdrawals.withdraw(new WithdrawalCommand(accountId, 1_00L, "DOP"), key, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.CONFLICT);

    assertThat(auditCount("WITHDRAWAL_CONFLICT")).isEqualTo(1);
    assertThat(auditCount("WITHDRAWAL_CONFIRMED")).isZero();
    Long debits =
        jdbc.queryForObject(
            "select count(*) from movements where account_id = ? and direction = 'DEBIT'",
            Long.class,
            accountId);
    assertThat(debits).isZero();
  }

  @Test
  void confirmedReplayRecordsNoSecondAudit() {
    String key = "w-rp-" + tag;
    withdrawals.withdraw(new WithdrawalCommand(accountId, 40_00L, "DOP"), key, actor);
    WithdrawalResult replayed =
        withdrawals.withdraw(new WithdrawalCommand(accountId, 40_00L, "DOP"), key, actor);

    assertThat(replayed.created()).isFalse();
    assertThat(auditCount("WITHDRAWAL_CONFIRMED")).isEqualTo(1);
  }

  @Test
  void rejectedReplayRecordsNoSecondAudit() {
    String key = "w-rpr-" + tag;
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(new WithdrawalCommand(accountId, 999_00L, "DOP"), key, actor))
        .isInstanceOf(WithdrawalOperationException.class);
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(new WithdrawalCommand(accountId, 999_00L, "DOP"), key, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.REJECTED);

    assertThat(auditCount("WITHDRAWAL_REJECTED")).isEqualTo(1);
  }
}
