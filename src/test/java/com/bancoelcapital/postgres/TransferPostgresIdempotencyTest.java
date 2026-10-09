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
 * Idempotency guarantees against real PostgreSQL: same key plus same payload replays without a
 * second effect; same key plus different payload conflicts without moving money.
 */
class TransferPostgresIdempotencyTest extends AbstractPostgresTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String sourceHolder = "holder-pgti-src-" + tag;
    String destinationHolder = "holder-pgti-dst-" + tag;
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    var destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
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
  }

  @Test
  void sameKeySamePayloadReplaysWithoutSecondEffect() {
    String key = "t-idem-" + tag;
    TransferResult first =
        transfers.transfer(
            new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), key, sourceActor);
    TransferResult replayed =
        transfers.transfer(
            new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), key, sourceActor);

    assertThat(first.created()).isTrue();
    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(first.operationId());
    assertThat(transferDebitCount()).isEqualTo(1);
    assertThat(transferCreditCount()).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void sameKeyDifferentPayloadConflictsWithoutMovingMoney() {
    String key = "t-cfl-" + tag;
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 999_00L, "DOP"), key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 10_00L, "DOP"), key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);

    assertThat(transferDebitCount()).isZero();
    assertThat(transferCreditCount()).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT' and"
                    + " resource_id = ?",
                key))
        .isEqualTo(1);
  }

  private long transferDebitCount() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id = m.operation_id"
                + " where m.account_id = ? and m.direction = 'DEBIT' and o.operation_type ="
                + " 'TRANSFER'",
            Long.class,
            sourceId);
    return count == null ? 0 : count;
  }

  private long transferCreditCount() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id = m.operation_id"
                + " where m.account_id = ? and m.direction = 'CREDIT' and o.operation_type ="
                + " 'TRANSFER'",
            Long.class,
            destinationId);
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
