package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bancoelcapital.accounts.api.AccountCreditCommand;
import com.bancoelcapital.accounts.api.AccountCreditResult;
import com.bancoelcapital.accounts.api.AccountCreditService;
import com.bancoelcapital.accounts.api.AccountDebitCommand;
import com.bancoelcapital.accounts.api.AccountDebitResult;
import com.bancoelcapital.accounts.api.AccountDebitService;
import com.bancoelcapital.accounts.api.AccountLookupService;
import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.accounts.internal.AccountTransition;
import com.bancoelcapital.accounts.internal.AccountTransitionService;
import com.bancoelcapital.accounts.internal.TransitionCommand;
import com.bancoelcapital.audit.api.AuditEntry;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * H-1 regression proofs on H2: when the transfer call participates in an outer transaction, a
 * same-key collision must still reload and resolve the committed winner without reusing the
 * caller's rollback-only transaction. Both deterministic collision paths are exercised: an early
 * rejection and a first-leg-applied/second-leg-refused rejection.
 *
 * <p>No sleeps: service spies hold the loser at deterministic points while the winner commits.
 * PostgreSQL has an equivalent suite; H2 alone cannot prove the PostgreSQL aborted-transaction
 * behavior ({@code 25P02}).
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-external-tx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferExternalTransactionRecoveryTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired AccountTransitionService transitions;

  @Autowired JdbcTemplate jdbc;

  @Autowired PlatformTransactionManager transactionManager;

  @MockitoSpyBean AccountLookupService lookup;

  @MockitoSpyBean AccountDebitService debits;

  @MockitoSpyBean AccountCreditService credits;

  @MockitoSpyBean AuditRecorder audit;

  @MockitoSpyBean AuthorizationService authorization;

  String tag;
  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;
  TransactionTemplate outer;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    sourceActor = new AuthenticatedActor("holder-text-src-" + tag, Set.of());
    destinationActor = new AuthenticatedActor("holder-text-dst-" + tag, Set.of());
    customers.create(
        new CustomerCreationCommand(sourceActor.subject(), null), "c-text-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationActor.subject(), null),
        "d-text-" + tag,
        destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-text-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationActor.subject(), "DOP", "BASIC"),
                "b-text-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-text-" + tag, sourceActor);
    outer = new TransactionTemplate(transactionManager);
  }

  @Test
  void earlyRejectionCollisionInsideCallerTransactionRecoversWinner() throws Exception {
    UUID unknownDestination = UUID.randomUUID();
    String key = "text-early-" + tag;
    TransferCommand losingCommand =
        new TransferCommand(sourceId, unknownDestination, 40_00L, "DOP");
    TransferCommand winnerCommand = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
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
      Future<TransferResult> loser =
          pool.submit(
              () -> outer.execute(status -> transfers.transfer(losingCommand, key, sourceActor)));
      assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

      winner = transfers.transfer(winnerCommand, key, sourceActor);
      gate.countDown();

      try {
        loser.get(60, TimeUnit.SECONDS);
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
    assertThat(conflictAudits(key)).isEqualTo(1);
    assertThat(rejectionAudits(key)).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void secondLegRejectionCollisionInsideCallerTransactionRecoversWinner() throws Exception {
    UUID firstLoserAccount =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-text-loser-a-" + tag,
                sourceActor)
            .accountId();
    UUID secondLoserAccount =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-text-loser-b-" + tag,
                sourceActor)
            .accountId();
    UUID losingSourceId =
        firstLoserAccount.compareTo(secondLoserAccount) < 0
            ? firstLoserAccount
            : secondLoserAccount;
    UUID orderedDestination =
        firstLoserAccount.compareTo(secondLoserAccount) < 0
            ? secondLoserAccount
            : firstLoserAccount;
    deposits.deposit(
        new DepositCommand(losingSourceId, 100_00L, "DOP"), "f-text-loser-" + tag, sourceActor);
    AuthenticatedActor employee =
        new AuthenticatedActor("employee-text-" + tag, Set.of("BANK_EMPLOYEE"));
    transitions.transition(
        new TransitionCommand(orderedDestination, AccountTransition.BLOCK),
        "blk-text-" + tag,
        employee);
    UUID winnerSourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-text-winner-" + tag,
                sourceActor)
            .accountId();
    deposits.deposit(
        new DepositCommand(winnerSourceId, 100_00L, "DOP"), "f-text-winner-" + tag, sourceActor);
    String key = "text-second-leg-" + tag;
    long losingAmount = 40_00L;
    TransferCommand losingCommand =
        new TransferCommand(losingSourceId, orderedDestination, losingAmount, "DOP");
    TransferCommand winnerCommand =
        new TransferCommand(winnerSourceId, destinationId, 10_00L, "DOP");
    CountDownLatch rejectionEntered = new CountDownLatch(1);
    CountDownLatch rejectionGate = new CountDownLatch(1);
    List<AccountDebitCommand> debitAttempts = new CopyOnWriteArrayList<>();
    Map<UUID, AccountDebitResult> debitResults = new ConcurrentHashMap<>();
    doAnswer(
            invocation -> {
              AccountDebitCommand debit = invocation.getArgument(0);
              AccountDebitResult result = (AccountDebitResult) invocation.callRealMethod();
              debitAttempts.add(debit);
              debitResults.put(debit.accountId(), result);
              return result;
            })
        .when(debits)
        .applyDebit(any());
    Map<UUID, AccountCreditResult> creditResults = new ConcurrentHashMap<>();
    doAnswer(
            invocation -> {
              AccountCreditCommand credit = invocation.getArgument(0);
              AccountCreditResult result = (AccountCreditResult) invocation.callRealMethod();
              creditResults.put(credit.accountId(), result);
              return result;
            })
        .when(credits)
        .applyCredit(any());
    List<AuditEntry> rejectionEntries = new CopyOnWriteArrayList<>();
    doAnswer(
            invocation -> {
              AuditEntry entry = invocation.getArgument(0);
              if ("TRANSFER_REJECTED".equals(entry.action())) {
                rejectionEntries.add(entry);
                rejectionEntered.countDown();
                if (!rejectionGate.await(30, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("rejection barrier timeout");
                }
              }
              return invocation.callRealMethod();
            })
        .when(audit)
        .record(any());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    TransferResult winner;
    try {
      Future<TransferResult> loser =
          pool.submit(
              () -> outer.execute(status -> transfers.transfer(losingCommand, key, sourceActor)));
      assertThat(rejectionEntered.await(30, TimeUnit.SECONDS)).isTrue();

      winner = transfers.transfer(winnerCommand, key, sourceActor);
      rejectionGate.countDown();

      try {
        loser.get(60, TimeUnit.SECONDS);
        assertThat(false).as("expected the losing rejection to conflict").isTrue();
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(TransferOperationException.class);
        assertThat(((TransferOperationException) e.getCause()).getKind())
            .isEqualTo(TransferOperationException.Kind.CONFLICT);
      }
    } finally {
      rejectionGate.countDown();
      pool.shutdownNow();
    }

    assertThat(winner.created()).isTrue();
    Map<String, Object> row = intentRow(key);
    assertThat(row.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(row.get("operation_id")).isEqualTo(winner.operationId());
    assertThat(operationMovements(winner.operationId())).isEqualTo(2);
    assertThat(transferOperationCount(losingSourceId, winnerSourceId)).isEqualTo(1);
    assertThat(usageCount(winner.operationId())).isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                    + " and resource_id = ?",
                winner.operationId().toString()))
        .isEqualTo(1);
    assertThat(conflictAudits(key)).isEqualTo(1);
    assertThat(rejectionAudits(key)).isZero();
    assertThat(balanceOf(losingSourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(winnerSourceId)).isEqualTo(90_00L);
    assertThat(balanceOf(orderedDestination)).isZero();
    assertThat(balanceOf(destinationId)).isEqualTo(10_00L);

    // The losing transfer attempted the debit for the expected source and amount, and the real
    // Accounts contract applied it inside the attempt; the balances above prove the write
    // transaction rolled that effect back. The refusal came from the really blocked destination
    // through the credit leg, and the rejection element records that cause before the collision.
    assertThat(debitAttempts)
        .containsExactlyInAnyOrder(
            new AccountDebitCommand(losingSourceId, losingAmount, "DOP"),
            new AccountDebitCommand(winnerSourceId, 10_00L, "DOP"));
    assertThat(debitResults.get(losingSourceId).outcome())
        .isEqualTo(AccountDebitResult.AccountDebitOutcome.APPLIED);
    assertThat(creditResults.get(orderedDestination).outcome())
        .isEqualTo(AccountCreditResult.AccountCreditOutcome.NOT_OPERABLE);
    assertThat(rejectionEntries).hasSize(1);
    assertThat(rejectionEntries.get(0).action()).isEqualTo("TRANSFER_REJECTED");
    assertThat(rejectionEntries.get(0).resourceId()).isEqualTo(key);
    assertThat(rejectionEntries.get(0).result()).isEqualTo("REJECTED");
    assertThat(rejectionEntries.get(0).details())
        .isEqualTo("destination account not operable for transfers");
  }

  @Test
  void callerRollbackAfterSuccessfulTransferLeavesNoTransactionalEffects() {
    String key = "text-caller-rollback-" + tag;
    TransferResult result =
        outer.execute(
            status -> {
              TransferResult confirmed =
                  transfers.transfer(
                      new TransferCommand(sourceId, destinationId, 40_00L, "DOP"),
                      key,
                      sourceActor);
              status.setRollbackOnly();
              return confirmed;
            });

    assertThat(result).isNotNull();
    assertThat(result.created()).isTrue();
    assertThat(
            count(
                "select count(*) from transfer_operation_idempotency where idempotency_key = ?",
                key))
        .isZero();
    assertThat(
            count(
                "select count(*) from financial_operations where operation_type = 'TRANSFER'"
                    + " and account_id = ?",
                sourceId))
        .isZero();
    assertThat(
            count(
                "select count(*) from movements m join financial_operations o"
                    + " on o.id = m.operation_id where o.operation_type = 'TRANSFER'"
                    + " and o.account_id = ?",
                sourceId))
        .isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                    + " and resource_id = ?",
                result.operationId().toString()))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  @Test
  void serviceManagedTransactionIsActiveAndPinsReadCommitted() {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean active = new AtomicBoolean();
    AtomicInteger isolation = new AtomicInteger(-1);
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                active.set(TransactionSynchronizationManager.isActualTransactionActive());
                isolation.set(
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
              }
              return invocation.callRealMethod();
            })
        .when(authorization)
        .decideTransfer(any(), any());

    transfers.transfer(
        new TransferCommand(sourceId, destinationId, 40_00L, "DOP"),
        "text-managed-" + tag,
        sourceActor);

    assertThat(active).isTrue();
    assertThat(isolation.get()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
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
