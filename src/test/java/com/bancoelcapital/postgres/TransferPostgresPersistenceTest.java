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
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.financialops.transfer.TransferCommand;
import com.bancoelcapital.financialops.transfer.TransferOperationException;
import com.bancoelcapital.financialops.transfer.TransferResult;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Transfer persistence against real PostgreSQL (ADR-18, Option A): confirmed persists operation
 * (account_id identifying the source), linked DEBIT plus CREDIT movements, both balances,
 * idempotency and audit in one transaction; rejected persists operation, idempotency and audit with
 * no movement and no balance change on either side.
 */
class TransferPostgresPersistenceTest extends AbstractPostgresTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String sourceHolder = "holder-pgtp-src-" + tag;
    String destinationHolder = "holder-pgtp-dst-" + tag;
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-tps-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "c-tpd-" + tag, destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceHolder, "DOP", "BASIC"),
                "a-tps-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "a-tpd-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tps-" + tag, sourceActor);
  }

  @Test
  void confirmedTransferPersistsAllEffectsAtomically() {
    TransferResult result =
        transfers.transfer(
            new TransferCommand(sourceId, destinationId, 40_00L, "DOP"),
            "t-ok-" + tag,
            sourceActor);

    assertThat(result.created()).isTrue();
    assertThat(
            count(
                "select count(*) from financial_operations where id = ? and status = 'CONFIRMED'"
                    + " and operation_type = 'TRANSFER' and account_id = ?",
                result.operationId(),
                sourceId))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from movements where operation_id = ? and direction = 'DEBIT'"
                    + " and account_id = ?",
                result.operationId(),
                sourceId))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from movements where operation_id = ? and direction = 'CREDIT'"
                    + " and account_id = ?",
                result.operationId(),
                destinationId))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from transfer_operation_idempotency where idempotency_key = ?"
                    + " and outcome = 'CONFIRMED'",
                "t-ok-" + tag))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED' and"
                    + " resource_id = ?",
                result.operationId().toString()))
        .isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void rejectedTransferPersistsEvidenceWithoutFinancialEffect() {
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 999_00L, "DOP"),
                    "t-rej-" + tag,
                    sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    assertThat(
            count(
                "select count(*) from financial_operations where account_id = ?"
                    + " and operation_type = 'TRANSFER' and status = 'REJECTED'",
                sourceId))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from transfer_operation_idempotency where idempotency_key = ?"
                    + " and outcome = 'REJECTED'",
                "t-rej-" + tag))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from movements where account_id = ? and direction = 'DEBIT'",
                sourceId))
        .isZero();
    assertThat(
            count(
                "select count(*) from movements where account_id = ? and direction = 'CREDIT'",
                destinationId))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  @Test
  void exactBalanceTransferLeavesZeroSource() {
    TransferResult result =
        transfers.transfer(
            new TransferCommand(sourceId, destinationId, 100_00L, "DOP"),
            "t-exact-" + tag,
            sourceActor);

    assertThat(result.created()).isTrue();
    assertThat(balanceOf(sourceId)).isZero();
    assertThat(balanceOf(destinationId)).isEqualTo(100_00L);
  }

  @Test
  void currencyMismatchIsRejectedWithoutEffect() {
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 10_00L, "USD"),
                    "t-cur-" + tag,
                    sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    assertThat(
            count(
                "select count(*) from movements where account_id = ? and direction = 'DEBIT'",
                sourceId))
        .isZero();
    assertThat(count("select count(*) from movements where account_id = ?", destinationId))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
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
