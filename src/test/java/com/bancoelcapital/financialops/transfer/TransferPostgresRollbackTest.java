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
import com.bancoelcapital.postgres.AbstractPostgresTest;

/**
 * Real PostgreSQL rollback proof: a technical failure mid-confirmation — including a failure
 * between the debit and the credit legs, where PostgreSQL aborts the whole transaction — leaves no
 * transfer operation, no movement, no transfer idempotency, no balance change and no false
 * TRANSFER_CONFIRMED audit behind. Cleanup is scoped to TRANSFER_* audit actions only, so suites
 * sharing the container keep their own evidence.
 */
class TransferPostgresRollbackTest extends AbstractPostgresTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @MockitoBean MovementRepository movements;

  @MockitoSpyBean AccountCreditService credits;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String sourceHolder = "holder-pgrb-src-" + tag;
    String destinationHolder = "holder-pgrb-dst-" + tag;
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "d-" + tag, destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceHolder, "DOP", "BASIC"), "a-" + tag, sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "b-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-" + tag, sourceActor);
    jdbc.execute("delete from audit_events where action like 'TRANSFER_%'");
  }

  @Test
  void technicalFailureRollsBackEveryTransferEffectOnPostgres() {
    doThrow(new RuntimeException("boom")).when(movements).save(any(Movement.class));

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 40_00L, "DOP"),
                    "t-boom-" + tag,
                    sourceActor))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(transferOperationCount()).isZero();
    assertThat(
            count(
                "select count(*) from transfer_operation_idempotency where idempotency_key = ?",
                "t-boom-" + tag))
        .isZero();
    assertThat(count("select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  @Test
  void failureBetweenLegsRollsBackFirstLegEffectOnPostgres() {
    doThrow(new RuntimeException("boom"))
        .when(credits)
        .applyCredit(
            argThat((AccountCreditCommand command) -> destinationId.equals(command.accountId())));

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 40_00L, "DOP"),
                    "t-legs-" + tag,
                    sourceActor))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(transferOperationCount()).isZero();
    assertThat(
            count(
                "select count(*) from transfer_operation_idempotency where idempotency_key = ?",
                "t-legs-" + tag))
        .isZero();
    assertThat(count("select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  private int transferOperationCount() {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from financial_operations where account_id = ?"
                + " and operation_type = 'TRANSFER'",
            Integer.class,
            sourceId);
    return count == null ? 0 : count;
  }

  private int count(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
