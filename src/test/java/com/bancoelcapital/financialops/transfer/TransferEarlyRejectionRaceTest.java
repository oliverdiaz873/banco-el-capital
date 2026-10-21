package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.Map;
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
import org.springframework.boot.test.context.SpringBootTest;
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
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Deterministic H2 proofs for same-key persistence collisions. A loser arriving through either a
 * deterministic early rejection or a first-leg debit failure cannot overwrite a winner that commits
 * between the loser's pre-check and its rejection insert. Latch-controlled service spies make the
 * ordering explicit; no sleeps are used.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-early-rejection;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferEarlyRejectionRaceTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @MockitoSpyBean AccountLookupService lookup;

  @MockitoSpyBean AccountDebitService debits;

  String tag;
  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    sourceActor = new AuthenticatedActor("holder-terr-src-" + tag, Set.of());
    destinationActor = new AuthenticatedActor("holder-terr-dst-" + tag, Set.of());
    customers.create(
        new CustomerCreationCommand(sourceActor.subject(), null), "c-terr-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationActor.subject(), null),
        "d-terr-" + tag,
        destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-terr-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationActor.subject(), "DOP", "BASIC"),
                "b-terr-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-terr-" + tag, sourceActor);
  }

  @Test
  void earlyRejectionAfterWinnerCommitCannotOverwriteIt() throws Exception {
    UUID unknownDestination = UUID.randomUUID();
    String key = "terr-race-" + tag;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              UUID accountId = invocation.getArgument(0);
              if (unknownDestination.equals(accountId)) {
                entered.countDown();
                if (!gate.await(30, TimeUnit.SECONDS)) {
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
      assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
      winner =
          transfers.transfer(
              new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), key, sourceActor);
      gate.countDown();

      try {
        losingEarlyRejection.get(60, TimeUnit.SECONDS);
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
    Map<String, Object> row = intentRow(key);
    assertThat(row.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(row.get("operation_id")).isEqualTo(winner.operationId());
    assertThat(operationMovements(winner.operationId())).isEqualTo(2);
    assertThat(transferOperationCount(sourceId, destinationId)).isEqualTo(1);
    assertThat(usageCount(winner.operationId())).isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                    + " and resource_id = ?",
                winner.operationId().toString()))
        .isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
    assertThat(conflictAudits(key)).isEqualTo(1);
    assertThat(rejectionAudits(key)).isZero();
  }

  @Test
  void earlyRejectionAgainstRejectedWinnerReplaysWithoutSecondRejection() throws Exception {
    UUID unknownDestination = UUID.randomUUID();
    String key = "terr-rejected-winner-" + tag;
    TransferCommand command = new TransferCommand(sourceId, unknownDestination, 40_00L, "DOP");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger unknownLookups = new AtomicInteger();
    doAnswer(
            invocation -> {
              UUID accountId = invocation.getArgument(0);
              if (unknownDestination.equals(accountId) && unknownLookups.incrementAndGet() == 1) {
                entered.countDown();
                if (!gate.await(30, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("barrier gate timeout");
                }
              }
              return invocation.callRealMethod();
            })
        .when(lookup)
        .findHolderCustomerId(any());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    Map<String, Object> winnerRow;
    try {
      Future<TransferResult> losingRejection =
          pool.submit(() -> transfers.transfer(command, key, sourceActor));
      assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
          .isInstanceOf(TransferOperationException.class)
          .matches(
              e ->
                  ((TransferOperationException) e).getKind()
                      == TransferOperationException.Kind.REJECTED);
      winnerRow = intentRow(key);
      gate.countDown();

      try {
        losingRejection.get(60, TimeUnit.SECONDS);
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
    assertThat(operationMovements(winnerRow.get("operation_id"))).isZero();
    assertThat(usageCount(winnerRow.get("operation_id"))).isZero();
    assertThat(rejectionAudits(key)).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                    + " and resource_id = ?",
                winnerRow.get("operation_id").toString()))
        .isZero();
    assertThat(conflictAudits(key)).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  @Test
  void firstLegFailureAfterWinnerCommitCannotOverwriteIt() throws Exception {
    UUID secondSourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-terr-win-" + tag,
                sourceActor)
            .accountId();
    deposits.deposit(
        new DepositCommand(secondSourceId, 100_00L, "DOP"), "f-terr-win-" + tag, sourceActor);
    UUID firstLoserAccount =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-terr-loser-a-" + tag,
                sourceActor)
            .accountId();
    UUID secondLoserAccount =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-terr-loser-b-" + tag,
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
        new DepositCommand(losingSourceId, 100_00L, "DOP"), "f-terr-loser-" + tag, sourceActor);
    String key = "terr-first-leg-" + tag;
    long losingAmount = 999_00L;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              AccountDebitCommand debit = invocation.getArgument(0);
              if (debit.amountMinorUnits() == losingAmount) {
                entered.countDown();
                if (!gate.await(30, TimeUnit.SECONDS)) {
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
      assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
      winner =
          transfers.transfer(
              new TransferCommand(secondSourceId, destinationId, 10_00L, "DOP"), key, sourceActor);
      gate.countDown();

      try {
        losingFirstLeg.get(60, TimeUnit.SECONDS);
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
    Map<String, Object> row = intentRow(key);
    assertThat(row.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(row.get("operation_id")).isEqualTo(winner.operationId());
    assertThat(operationMovements(winner.operationId())).isEqualTo(2);
    assertThat(
            transferOperationCount(
                sourceId, secondSourceId, losingSourceId, losingDestinationId, destinationId))
        .isEqualTo(1);
    assertThat(usageCount(winner.operationId())).isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                    + " and resource_id = ?",
                winner.operationId().toString()))
        .isEqualTo(1);
    assertThat(balanceOf(losingSourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(losingDestinationId)).isZero();
    assertThat(balanceOf(secondSourceId)).isEqualTo(90_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(10_00L);
    assertThat(conflictAudits(key)).isEqualTo(1);
    assertThat(rejectionAudits(key)).isZero();
  }

  private Map<String, Object> intentRow(String key) {
    return jdbc.queryForMap(
        "select operation_id, outcome, request_hash, source_account_id, beneficiary_id, path,"
            + " amount_minor_units, currency from transfer_operation_idempotency"
            + " where idempotency_key = ?",
        key);
  }

  private int operationMovements(Object operationId) {
    Integer total =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id ="
                + " m.operation_id where o.id = ?",
            Integer.class,
            operationId);
    return total == null ? 0 : total;
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

  private int usageCount(Object operationId) {
    return count(
        "select count(*) from transfer_beneficiary_usage where operation_id = ?", operationId);
  }

  private int rejectionAudits(String key) {
    return count(
        "select count(*) from audit_events where action = 'TRANSFER_REJECTED' and resource_id = ?",
        key);
  }

  private int conflictAudits(String key) {
    return count(
        "select count(*) from audit_events where action = 'TRANSFER_CONFLICT' and resource_id = ?",
        key);
  }

  private int count(String sql, Object... args) {
    Integer total = jdbc.queryForObject(sql, Integer.class, args);
    return total == null ? 0 : total;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
