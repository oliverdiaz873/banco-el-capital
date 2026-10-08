package com.bancoelcapital.financialops.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.core.Movement;
import com.bancoelcapital.financialops.core.MovementRepository;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Atomicity proof on H2 (PG semantics deferred to Testcontainers when Docker exists). A
 * confirmation that fails mid-transaction must leave no operation, no movement, no idempotency, no
 * balance change, and no audit behind.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:deposit-atomicity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class DepositAtomicityTest {

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @MockitoBean MovementRepository movements;

  UUID accountId;
  AuthenticatedActor actor = new AuthenticatedActor("holder-atom", Set.of());

  @BeforeEach
  void setup() {
    customers.create(new CustomerCreationCommand("holder-atom", null), "c-atom", actor);
    var created =
        accounts.create(new AccountCreationCommand("holder-atom", "DOP", "BASIC"), "a-atom", actor);
    accountId = created.accountId();
  }

  @Test
  void rolledBackConfirmationLeavesNoBusinessEvidence() {
    doThrow(new RuntimeException("boom")).when(movements).save(any(Movement.class));

    assertThatThrownBy(
            () -> deposits.deposit(new DepositCommand(accountId, 10_00L, "DOP"), "d-boom", actor))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(count("select count(*) from financial_operations")).isZero();
    assertThat(count("select count(*) from movements")).isZero();
    assertThat(
            count(
                "select count(*) from deposit_operation_idempotency"
                    + " where idempotency_key = 'd-boom'"))
        .isZero();
    assertThat(count("select count(*) from audit_events where action = 'DEPOSIT_CONFIRMED'"))
        .isZero();
    Long balance =
        jdbc.queryForObject(
            "select balance_minor_units from accounts where id = ?", Long.class, accountId);
    assertThat(balance).isZero();
  }

  private int count(String sql) {
    Integer count = jdbc.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }
}
