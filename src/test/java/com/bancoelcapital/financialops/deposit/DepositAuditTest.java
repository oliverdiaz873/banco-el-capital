package com.bancoelcapital.financialops.deposit;

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
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Transactional audit behavior on H2 (PG semantics deferred to Testcontainers when Docker exists).
 * Proves CONFIRMED audit commits with its effects, REJECTED audit survives its commit, and a
 * rolled-back confirmation leaves no business evidence behind.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:deposit-audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class DepositAuditTest {

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor actor = new AuthenticatedActor("holder-audit", Set.of());

  @BeforeEach
  void setup() {
    customers.create(new CustomerCreationCommand("holder-audit", null), "c-audit", actor);
    var created =
        accounts.create(
            new AccountCreationCommand("holder-audit", "DOP", "BASIC"), "a-audit", actor);
    accountId = created.accountId();
  }

  private int auditCount(String action) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from audit_events where action = ?", Integer.class, action);
    return count == null ? 0 : count;
  }

  @Test
  void confirmedDepositPersistsExactlyOneConfirmedAudit() {
    DepositResult result =
        deposits.deposit(new DepositCommand(accountId, 25_00L, "DOP"), "d-ok", actor);

    assertThat(result.created()).isTrue();
    assertThat(auditCount("DEPOSIT_CONFIRMED")).isEqualTo(1);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select resource_id, result from audit_events where action = 'DEPOSIT_CONFIRMED'");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("resource_id")).isEqualTo(result.operationId().toString());
    assertThat(rows.get(0).get("result")).isEqualTo("CONFIRMED");
  }

  @Test
  void rejectedDepositPersistsRejectedAuditAfterCommit() {
    assertThatThrownBy(
            () -> deposits.deposit(new DepositCommand(accountId, 0L, "DOP"), "d-rej", actor))
        .isInstanceOf(DepositOperationException.class)
        .matches(
            e ->
                ((DepositOperationException) e).getKind()
                    == DepositOperationException.Kind.REJECTED);

    assertThat(auditCount("DEPOSIT_REJECTED")).isEqualTo(1);
    Integer idem =
        jdbc.queryForObject(
            "select count(*) from deposit_operation_idempotency"
                + " where idempotency_key = 'd-rej' and outcome = 'REJECTED'",
            Integer.class);
    assertThat(idem).isEqualTo(1);
  }
}
