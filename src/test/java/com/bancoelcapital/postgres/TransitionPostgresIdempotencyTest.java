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
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Idempotency guarantees against real PostgreSQL: same key plus same payload replays without a
 * second effect or audit; same key plus different payload conflicts without changing state.
 */
class TransitionPostgresIdempotencyTest extends AbstractPostgresTest {

  @Autowired AccountTransitionService transitions;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor employee;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-pgti-" + tag;
    employee = new AuthenticatedActor("emp-" + tag, Set.of("BANK_EMPLOYEE"));
    var holderActor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, holderActor);
    accountId =
        accounts
            .create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, holderActor)
            .accountId();
  }

  @Test
  void sameKeySamePayloadReplaysWithoutSecondEffect() {
    String key = "t-idem-" + tag;
    TransitionResult first =
        transitions.transition(
            new TransitionCommand(accountId, AccountTransition.BLOCK), key, employee);
    TransitionResult replayed =
        transitions.transition(
            new TransitionCommand(accountId, AccountTransition.BLOCK), key, employee);

    assertThat(first.changed()).isTrue();
    assertThat(replayed.changed()).isFalse();
    assertThat(replayed.status()).isEqualTo(AccountStatus.BLOCKED);
    assertThat(transitionIdempotencyCount(key)).isEqualTo(1);
    assertThat(status()).isEqualTo("BLOCKED");
  }

  @Test
  void sameKeyDifferentPayloadConflictsWithoutChangingState() {
    String key = "t-cfl-" + tag;
    transitions.transition(
        new TransitionCommand(accountId, AccountTransition.BLOCK), key, employee);
    assertThatThrownBy(
            () ->
                transitions.transition(
                    new TransitionCommand(accountId, AccountTransition.UNBLOCK), key, employee))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.CONFLICT);

    assertThat(status()).isEqualTo("BLOCKED");
    assertThat(transitionIdempotencyCount(key)).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'ACCOUNT_TRANSITION_CONFLICT'"
                    + " and resource_id = ?",
                key))
        .isEqualTo(1);
  }

  private int transitionIdempotencyCount(String key) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from account_transition_idempotency where idempotency_key = ?",
            Integer.class,
            key);
    return count == null ? 0 : count;
  }

  private int count(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }

  private String status() {
    return jdbc.queryForObject("select status from accounts where id = ?", String.class, accountId);
  }
}
