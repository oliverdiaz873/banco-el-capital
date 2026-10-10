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
import com.bancoelcapital.accounts.internal.AccountStatus;
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
 * Transition persistence against real PostgreSQL: confirmed persists the state effect, idempotency
 * and audit in one transaction; rejected persists idempotency and audit with no state change.
 * Flyway V9 provides the transition idempotency table.
 */
class TransitionPostgresPersistenceTest extends AbstractPostgresTest {

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
    String holder = "holder-pgtp-" + tag;
    employee = new AuthenticatedActor("emp-" + tag, Set.of("BANK_EMPLOYEE"));
    var holderActor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, holderActor);
    accountId =
        accounts
            .create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, holderActor)
            .accountId();
  }

  @Test
  void confirmedBlockPersistsStateIdempotencyAndAudit() {
    TransitionResult result =
        transitions.transition(
            new TransitionCommand(accountId, AccountTransition.BLOCK), "t-ok-" + tag, employee);

    assertThat(result.changed()).isTrue();
    assertThat(result.status()).isEqualTo(AccountStatus.BLOCKED);
    assertThat(status()).isEqualTo("BLOCKED");
    assertThat(
            count(
                "select count(*) from account_transition_idempotency where idempotency_key = ?"
                    + " and outcome = 'CONFIRMED'",
                "t-ok-" + tag))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'ACCOUNT_BLOCKED' and"
                    + " resource_id = ?",
                accountId.toString()))
        .isEqualTo(1);
  }

  @Test
  void rejectedUnknownAccountPersistsEvidenceWithoutState() {
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                transitions.transition(
                    new TransitionCommand(missing, AccountTransition.BLOCK),
                    "t-unk-" + tag,
                    employee))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.REJECTED);

    assertThat(
            count(
                "select count(*) from account_transition_idempotency where idempotency_key = ?"
                    + " and outcome = 'REJECTED'",
                "t-unk-" + tag))
        .isEqualTo(1);
  }

  @Test
  void closeRequiresZeroBalance() {
    deposits.deposit(new DepositCommand(accountId, 10_00L, "DOP"), "f-" + tag, employee);
    assertThatThrownBy(
            () ->
                transitions.transition(
                    new TransitionCommand(accountId, AccountTransition.CLOSE),
                    "t-bal-" + tag,
                    employee))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.REJECTED);

    assertThat(status()).isEqualTo("ACTIVE");
    assertThat(balance()).isEqualTo(10_00L);
  }

  @Test
  void closeOnClosedIsRejectedWithoutEffect() {
    transitions.transition(
        new TransitionCommand(accountId, AccountTransition.CLOSE), "t-c1-" + tag, employee);
    assertThatThrownBy(
            () ->
                transitions.transition(
                    new TransitionCommand(accountId, AccountTransition.CLOSE),
                    "t-c2-" + tag,
                    employee))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.REJECTED);

    assertThat(status()).isEqualTo("CLOSED");
  }

  private int count(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }

  private String status() {
    return jdbc.queryForObject("select status from accounts where id = ?", String.class, accountId);
  }

  private Long balance() {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
