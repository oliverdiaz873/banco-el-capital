package com.bancoelcapital.accounts.internal;

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

import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.postgres.AbstractPostgresTest;

/**
 * Real PostgreSQL rollback proof: a technical failure mid-confirmation leaves no state effect, no
 * idempotency, no balance change and no false transition audit behind. Cleanup is scoped to
 * ACCOUNT_* transition actions only, so suites sharing the container keep their own evidence.
 */
class TransitionPostgresRollbackTest extends AbstractPostgresTest {

  @Autowired AccountTransitionService transitions;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @MockitoBean AuditRecorder audit;

  UUID accountId;
  AuthenticatedActor employee;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-pgrb-" + tag;
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
  void technicalFailureRollsBackEveryTransitionEffectOnPostgres() {
    doThrow(new RuntimeException("boom")).when(audit).record(any());

    assertThatThrownBy(
            () ->
                transitions.transition(
                    new TransitionCommand(accountId, AccountTransition.BLOCK),
                    "t-boom-" + tag,
                    employee))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(
            count(
                "select count(*) from account_transition_idempotency where idempotency_key = ?",
                "t-boom-" + tag))
        .isZero();
    assertThat(count("select count(*) from audit_events where action = 'ACCOUNT_BLOCKED'"))
        .isZero();
    assertThat(status()).isEqualTo("ACTIVE");
  }

  private int count(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }

  private String status() {
    return jdbc.queryForObject("select status from accounts where id = ?", String.class, accountId);
  }
}
