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
import com.bancoelcapital.accounts.internal.AccountTransition;
import com.bancoelcapital.accounts.internal.AccountTransitionService;
import com.bancoelcapital.accounts.internal.TransitionCommand;
import com.bancoelcapital.accounts.internal.TransitionException;
import com.bancoelcapital.accounts.internal.TransitionResult;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Audit behavior against real PostgreSQL: confirmed, rejected and conflict outcomes each persist
 * exactly one event; replays record nothing new. Cleanup is scoped to transition actions only, so
 * suites sharing the container keep their own evidence.
 */
class TransitionPostgresAuditTest extends AbstractPostgresTest {

  @Autowired AccountTransitionService transitions;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor employee;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-pgta-" + tag;
    employee = new AuthenticatedActor("emp-" + tag, Set.of("BANK_EMPLOYEE"));
    var holderActor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, holderActor);
    accountId =
        accounts
            .create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, holderActor)
            .accountId();
    jdbc.execute(
        "delete from audit_events where action in ('ACCOUNT_BLOCKED', 'ACCOUNT_UNBLOCKED',"
            + " 'ACCOUNT_CLOSED', 'ACCOUNT_TRANSITION_REJECTED', 'ACCOUNT_TRANSITION_CONFLICT')");
  }

  @Test
  void confirmedTransitionsAuditOnceEach() {
    TransitionResult blocked =
        transitions.transition(
            new TransitionCommand(accountId, AccountTransition.BLOCK), "t-b-" + tag, employee);
    TransitionResult unblocked =
        transitions.transition(
            new TransitionCommand(accountId, AccountTransition.UNBLOCK), "t-u-" + tag, employee);
    TransitionResult closed =
        transitions.transition(
            new TransitionCommand(accountId, AccountTransition.CLOSE), "t-c-" + tag, employee);

    assertThat(blocked.changed()).isTrue();
    assertThat(unblocked.changed()).isTrue();
    assertThat(closed.changed()).isTrue();
    assertThat(auditCount("ACCOUNT_BLOCKED")).isEqualTo(1);
    assertThat(auditCount("ACCOUNT_UNBLOCKED")).isEqualTo(1);
    assertThat(auditCount("ACCOUNT_CLOSED")).isEqualTo(1);
  }

  @Test
  void rejectedTransitionAuditsRejectionOnly() {
    // Funded account: CLOSE rejects on non-zero balance (an empty account would confirm).
    deposits.deposit(new DepositCommand(accountId, 10_00L, "DOP"), "f-" + tag, employee);
    assertThatThrownBy(
            () ->
                transitions.transition(
                    new TransitionCommand(accountId, AccountTransition.CLOSE),
                    "t-rej-" + tag,
                    employee))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.REJECTED);

    assertThatThrownBy(
            () ->
                transitions.transition(
                    new TransitionCommand(UUID.randomUUID(), AccountTransition.BLOCK),
                    "t-unk-" + tag,
                    employee))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.REJECTED);

    assertThat(auditCount("ACCOUNT_TRANSITION_REJECTED")).isEqualTo(2);
    assertThat(auditCount("ACCOUNT_BLOCKED")).isZero();
  }

  @Test
  void conflictAuditsOnceWithoutNewEffects() {
    String key = "t-cfl-" + tag;
    transitions.transition(
        new TransitionCommand(accountId, AccountTransition.BLOCK), key, employee);
    assertThatThrownBy(
            () ->
                transitions.transition(
                    new TransitionCommand(accountId, AccountTransition.UNBLOCK), key, employee))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.CONFLICT);

    assertThat(auditCount("ACCOUNT_TRANSITION_CONFLICT")).isEqualTo(1);
    assertThat(auditCount("ACCOUNT_BLOCKED")).isEqualTo(1);
  }

  @Test
  void replaysRecordNoSecondAudit() {
    String confirmedKey = "t-rp-" + tag;
    transitions.transition(
        new TransitionCommand(accountId, AccountTransition.BLOCK), confirmedKey, employee);
    transitions.transition(
        new TransitionCommand(accountId, AccountTransition.BLOCK), confirmedKey, employee);

    assertThat(auditCount("ACCOUNT_BLOCKED")).isEqualTo(1);
  }

  private int auditCount(String action) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from audit_events where action = ?", Integer.class, action);
    return count == null ? 0 : count;
  }
}
