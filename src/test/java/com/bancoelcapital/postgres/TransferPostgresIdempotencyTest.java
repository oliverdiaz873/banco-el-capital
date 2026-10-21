package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.bancoelcapital.accounts.api.AccountDebitCommand;
import com.bancoelcapital.accounts.api.AccountDebitService;
import com.bancoelcapital.accounts.api.AccountLookupService;
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
 * second effect; same key plus different payload conflicts without moving money; existing rows are
 * never overwritten by deterministic rejections, early-rejection collisions, or first-leg failure
 * collisions.
 */
class TransferPostgresIdempotencyTest extends AbstractPostgresTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @MockitoSpyBean AccountLookupService lookup;

  @MockitoSpyBean AccountDebitService debits;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String sourceHolder = "holder-pgti-src-" + tag;
    String destinationHolder = "holder-pgti-dst-" + tag;
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
  void rejectedKeyReplaysOriginalRejectionWithoutOverwriting() {
    String key = "t-rej-replay-" + tag;
    TransferCommand command = new TransferCommand(sourceId, UUID.randomUUID(), 40_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);
    var before = intentRow(key);

    assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    assertThat(intentRow(key)).isEqualTo(before);
    assertThat(transferDebitCount()).isZero();
    assertThat(transferCreditCount()).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_REJECTED' and"
                    + " resource_id = ?",
                key))
        .isEqualTo(1);
  }

  @Test
  void earlyRejectionAfterWinnerCommitCannotOverwriteIt() throws Exception {
    UUID unknownDestination = UUID.randomUUID();
    String key = "t-early-race-" + tag;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              UUID accountId = invocation.getArgument(0);
              if (unknownDestination.equals(accountId)) {
                entered.countDown();
                if (!gate.await(60, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("barrier gate timeout");
                }
              }
              return invocation.callRealMethod();
            })
        .when(lookup)
        .findHolderCustomerId(any());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    TransferResult winner;
    try {
      Future<TransferResult> losingEarlyRejection =
          pool.submit(
              () ->
                  transfers.transfer(
                      new TransferCommand(sourceId, unknownDestination, 40_00L, "DOP"),
                      key,
                      sourceActor));
      assertThat(entered.await(60, TimeUnit.SECONDS)).isTrue();
      winner =
          transfers.transfer(
              new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), key, sourceActor);
      gate.countDown();

      try {
        losingEarlyRejection.get(120, TimeUnit.SECONDS);
        assertThat(false).as("expected the losing rejection to conflict").isTrue();
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(TransferOperationException.class);
        assertThat(((TransferOperationException) e.getCause()).getKind())
            .isEqualTo(TransferOperationException.Kind.CONFLICT);
      }
    } finally {
      gate.countDown();
      pool.shutdownNow();
    }

    assertThat(winner.created()).isTrue();
    var row = intentRow(key);
    assertThat(row.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(row.get("operation_id")).isEqualTo(winner.operationId());
    assertThat(transferDebitCount()).isEqualTo(1);
    assertThat(transferCreditCount()).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT' and"
                    + " resource_id = ?",
                key))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_REJECTED' and"
                    + " resource_id = ?",
                key))
        .isZero();
  }

  @Test
  void earlyRejectionAgainstRejectedWinnerReplaysWithoutSecondRejection() throws Exception {
    UUID unknownDestination = UUID.randomUUID();
    String key = "t-pg-rejected-winner-" + tag;
    TransferCommand command = new TransferCommand(sourceId, unknownDestination, 40_00L, "DOP");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger unknownLookups = new AtomicInteger();
    doAnswer(
            invocation -> {
              UUID accountId = invocation.getArgument(0);
              if (unknownDestination.equals(accountId) && unknownLookups.incrementAndGet() == 1) {
                entered.countDown();
                if (!gate.await(60, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("barrier gate timeout");
                }
              }
              return invocation.callRealMethod();
            })
        .when(lookup)
        .findHolderCustomerId(any());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    java.util.Map<String, Object> winnerRow;
    try {
      Future<TransferResult> losingRejection =
          pool.submit(() -> transfers.transfer(command, key, sourceActor));
      assertThat(entered.await(60, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
          .isInstanceOf(TransferOperationException.class)
          .matches(
              e ->
                  ((TransferOperationException) e).getKind()
                      == TransferOperationException.Kind.REJECTED);
      winnerRow = intentRow(key);
      gate.countDown();

      try {
        losingRejection.get(120, TimeUnit.SECONDS);
        assertThat(false).as("expected the losing rejection to replay").isTrue();
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(TransferOperationException.class);
        assertThat(((TransferOperationException) e.getCause()).getKind())
            .isEqualTo(TransferOperationException.Kind.REJECTED);
      }
    } finally {
      gate.countDown();
      pool.shutdownNow();
    }

    assertThat(intentRow(key)).isEqualTo(winnerRow);
    assertThat(winnerRow.get("outcome")).isEqualTo("REJECTED");
    assertThat(transferOperationCount(sourceId, destinationId)).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from movements where operation_id = ?",
                winnerRow.get("operation_id")))
        .isZero();
    assertThat(
            count(
                "select count(*) from transfer_beneficiary_usage where operation_id = ?",
                winnerRow.get("operation_id")))
        .isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_REJECTED' and"
                    + " resource_id = ?",
                key))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED' and"
                    + " resource_id = ?",
                winnerRow.get("operation_id").toString()))
        .isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT' and"
                    + " resource_id = ?",
                key))
        .isZero();
    assertThat(transferDebitCount()).isZero();
    assertThat(transferCreditCount()).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  @Test
  void firstLegFailureAfterWinnerCommitCannotOverwriteIt() throws Exception {
    UUID secondSourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-pg-first-win-" + tag,
                sourceActor)
            .accountId();
    deposits.deposit(
        new DepositCommand(secondSourceId, 100_00L, "DOP"), "f-pg-first-win-" + tag, sourceActor);
    UUID firstLoserAccount =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-pg-loser-a-" + tag,
                sourceActor)
            .accountId();
    UUID secondLoserAccount =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-pg-loser-b-" + tag,
                sourceActor)
            .accountId();
    UUID losingSourceId =
        firstLoserAccount.compareTo(secondLoserAccount) < 0
            ? firstLoserAccount
            : secondLoserAccount;
    UUID losingDestinationId =
        firstLoserAccount.compareTo(secondLoserAccount) < 0
            ? secondLoserAccount
            : firstLoserAccount;
    deposits.deposit(
        new DepositCommand(losingSourceId, 100_00L, "DOP"), "f-pg-loser-" + tag, sourceActor);
    String key = "t-pg-first-leg-" + tag;
    long losingAmount = 999_00L;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              AccountDebitCommand debit = invocation.getArgument(0);
              if (debit.amountMinorUnits() == losingAmount) {
                entered.countDown();
                if (!gate.await(60, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("barrier gate timeout");
                }
              }
              return invocation.callRealMethod();
            })
        .when(debits)
        .applyDebit(any());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    TransferResult winner;
    try {
      Future<TransferResult> losingFirstLeg =
          pool.submit(
              () ->
                  transfers.transfer(
                      new TransferCommand(losingSourceId, losingDestinationId, losingAmount, "DOP"),
                      key,
                      sourceActor));
      assertThat(entered.await(60, TimeUnit.SECONDS)).isTrue();
      winner =
          transfers.transfer(
              new TransferCommand(secondSourceId, destinationId, 10_00L, "DOP"), key, sourceActor);
      gate.countDown();

      try {
        losingFirstLeg.get(120, TimeUnit.SECONDS);
        assertThat(false).as("expected the losing first-leg failure to conflict").isTrue();
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(TransferOperationException.class);
        assertThat(((TransferOperationException) e.getCause()).getKind())
            .isEqualTo(TransferOperationException.Kind.CONFLICT);
      }
    } finally {
      gate.countDown();
      pool.shutdownNow();
    }

    assertThat(winner.created()).isTrue();
    var row = intentRow(key);
    assertThat(row.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(row.get("operation_id")).isEqualTo(winner.operationId());
    assertThat(count("select count(*) from movements where operation_id = ?", winner.operationId()))
        .isEqualTo(2);
    assertThat(transferOperationCount(losingSourceId, secondSourceId)).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from transfer_beneficiary_usage where operation_id = ?",
                winner.operationId()))
        .isZero();
    assertThat(balanceOf(losingSourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(losingDestinationId)).isZero();
    assertThat(balanceOf(secondSourceId)).isEqualTo(90_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(10_00L);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT' and"
                    + " resource_id = ?",
                key))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_REJECTED' and"
                    + " resource_id = ?",
                key))
        .isZero();
  }

  @Test
  void confirmedKeyWithUnknownDestinationConflictsWithoutOverwriting() {
    String key = "t-conf-unknown-dest-" + tag;
    TransferResult first =
        transfers.transfer(
            new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), key, sourceActor);
    var before = intentRow(key);

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, UUID.randomUUID(), 40_00L, "DOP"),
                    key,
                    sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);

    assertThat(intentRow(key)).isEqualTo(before);
    assertThat(first.created()).isTrue();
    assertThat(transferDebitCount()).isEqualTo(1);
    assertThat(transferCreditCount()).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT' and"
                    + " resource_id = ?",
                key))
        .isEqualTo(1);
  }

  @Test
  void insufficientFundsRejectionReplaysAfterFundingWithoutOverwrite() {
    String key = "t-rej-funded-" + tag;
    TransferCommand command = new TransferCommand(sourceId, destinationId, 999_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);
    var before = intentRow(key);

    deposits.deposit(new DepositCommand(sourceId, 1_000_00L, "DOP"), "f-topup-" + tag, sourceActor);
    assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    assertThat(intentRow(key)).isEqualTo(before);
    assertThat(transferDebitCount()).isZero();
    assertThat(transferCreditCount()).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(1_100_00L);
    assertThat(balanceOf(destinationId)).isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_REJECTED' and"
                    + " resource_id = ?",
                key))
        .isEqualTo(1);
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

  private java.util.Map<String, Object> intentRow(String key) {
    return jdbc.queryForMap(
        "select operation_id, outcome, request_hash, source_account_id, beneficiary_id, path,"
            + " amount_minor_units, currency from transfer_operation_idempotency"
            + " where idempotency_key = ?",
        key);
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

  private int transferOperationCount(UUID... accountIds) {
    StringBuilder sql =
        new StringBuilder(
            "select count(*) from financial_operations where operation_type = 'TRANSFER'"
                + " and account_id in (");
    sql.append("?,".repeat(accountIds.length));
    sql.setLength(sql.length() - 1);
    sql.append(")");
    return count(sql.toString(), (Object[]) accountIds);
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
