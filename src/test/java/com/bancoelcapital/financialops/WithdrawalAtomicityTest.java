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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bancoelcapital.accounts.AccountCreationCommand;
import com.bancoelcapital.accounts.AccountCreationService;
import com.bancoelcapital.customers.CustomerCreationCommand;
import com.bancoelcapital.customers.CustomerCreationService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Atomicity proof on H2 (PG semantics deferred to Testcontainers when Docker exists). A
 * confirmation that fails mid-transaction must leave no operation, no movement, no idempotency, and
 * no balance change behind.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:withdrawal-atomicity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class WithdrawalAtomicityTest {

  @Autowired WithdrawalService withdrawals;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @MockitoBean MovementRepository movements;

  UUID accountId;
  AuthenticatedActor actor = new AuthenticatedActor("holder-watom", Set.of());

  @BeforeEach
  void setup() {
    customers.create(new CustomerCreationCommand("holder-watom", null), "c-watom", actor);
    var created =
        accounts.create(
            new AccountCreationCommand("holder-watom", "DOP", "BASIC"), "a-watom", actor);
    accountId = created.accountId();
    deposits.deposit(new DepositCommand(accountId, 10_00L, "DOP"), "f-watom", actor);
  }

  @Test
  void rolledBackConfirmationLeavesNoBusinessEvidence() {
    doThrow(new RuntimeException("boom")).when(movements).save(any(Movement.class));

    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    new WithdrawalCommand(accountId, 4_00L, "DOP"), "w-boom", actor))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(
            count("select count(*) from financial_operations where operation_type = 'WITHDRAWAL'"))
        .isZero();
    assertThat(
            count(
                "select count(*) from withdrawal_operation_idempotency"
                    + " where idempotency_key = 'w-boom'"))
        .isZero();
    assertThat(count("select count(*) from audit_events where action = 'WITHDRAWAL_CONFIRMED'"))
        .isZero();
    Long balance =
        jdbc.queryForObject(
            "select balance_minor_units from accounts where id = ?", Long.class, accountId);
    assertThat(balance).isEqualTo(10_00L);
  }

  private int count(String sql) {
    Integer count = jdbc.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }
}
