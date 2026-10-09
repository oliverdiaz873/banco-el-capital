package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.bancoelcapital.accounts.api.AccountCreditCommand;
import com.bancoelcapital.accounts.api.AccountCreditService;
import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.core.Movement;
import com.bancoelcapital.financialops.core.MovementRepository;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Atomicity proof on H2 (PG semantics deferred to Testcontainers when Docker exists). A
 * confirmation that fails mid-transaction — including a failure between the debit and the credit
 * legs — must leave no transfer operation, no movement, no transfer idempotency, no balance change,
 * and no transfer audit behind.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-atomicity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferAtomicityTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @MockitoBean MovementRepository movements;

  @MockitoSpyBean AccountCreditService credits;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor = new AuthenticatedActor("holder-src", Set.of());
  AuthenticatedActor destinationActor = new AuthenticatedActor("holder-dst", Set.of());

  @BeforeEach
  void setup() {
    customers.create(new CustomerCreationCommand("holder-src", null), "c-src", sourceActor);
    customers.create(new CustomerCreationCommand("holder-dst", null), "c-dst", destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand("holder-src", "DOP", "BASIC"),
                "a-src-" + UUID.randomUUID(),
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand("holder-dst", "DOP", "BASIC"),
                "a-dst-" + UUID.randomUUID(),
                destinationActor)
            .accountId();
  }

  @Test
  void rolledBackConfirmationLeavesNoTransferEvidence() {
    deposits.deposit(new DepositCommand(sourceId, 10_00L, "DOP"), "d-fund-rollback", sourceActor);
    doThrow(new RuntimeException("boom")).when(movements).save(any(Movement.class));

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 10_00L, "DOP"),
                    "t-boom",
                    sourceActor))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(transferOperationCount()).isZero();
    assertThat(count("select count(*) from movements")).isZero();
    assertThat(
            count(
                "select count(*) from transfer_operation_idempotency"
                    + " where idempotency_key = 't-boom'"))
        .isZero();
    assertThat(count("select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"))
        .isZero();
    // The funded source balance is intact and the destination never moved.
    assertThat(balanceOf(sourceId)).isEqualTo(10_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  @Test
  void failureBetweenLegsRollsBackFirstLegEffect() {
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "d-fund-legs", sourceActor);
    doThrow(new RuntimeException("boom"))
        .when(credits)
        .applyCredit(
            argThat((AccountCreditCommand command) -> destinationId.equals(command.accountId())));

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 10_00L, "DOP"),
                    "t-legs",
                    sourceActor))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(transferOperationCount()).isZero();
    assertThat(count("select count(*) from movements")).isZero();
    assertThat(
            count(
                "select count(*) from transfer_operation_idempotency"
                    + " where idempotency_key = 't-legs'"))
        .isZero();
    assertThat(count("select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"))
        .isZero();
    // The funded source balance is intact and the destination never moved.
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }

  private int transferOperationCount() {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from financial_operations where operation_type = 'TRANSFER'",
            Integer.class);
    return count == null ? 0 : count;
  }

  private int count(String sql) {
    Integer count = jdbc.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }
}
