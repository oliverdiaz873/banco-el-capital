package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.beneficiaries.api.BeneficiaryResolver;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryCommand;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.financialops.transfer.TransferCommand;
import com.bancoelcapital.financialops.transfer.TransferOperationException;
import com.bancoelcapital.financialops.transfer.TransferResult;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * E5 authoritative proof on real PostgreSQL (H2 mirrors live in {@code
 * TransferBeneficiaryBarrierTest}). Deletion races are coordinated with latches, never sleeps;
 * timeouts fail and double as deadlock detectors. Skipped without Docker or {@code BANCO_PG_*}
 * configuration — a skip proves nothing and the CI gate forbids it.
 */
class TransferBeneficiaryPostgresBarrierTest extends AbstractPostgresTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired BeneficiaryService beneficiaries;

  @Autowired JdbcTemplate jdbc;

  @MockitoSpyBean BeneficiaryResolver resolver;

  @MockitoSpyBean AuthorizationService authorization;

  String tag;
  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    sourceActor = new AuthenticatedActor("holder-tbp-src-" + tag, Set.of());
    destinationActor = new AuthenticatedActor("holder-tbp-dst-" + tag, Set.of());
    customers.create(
        new CustomerCreationCommand(sourceActor.subject(), null), "c-tbp-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationActor.subject(), null),
        "d-tbp-" + tag,
        destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-tbp-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationActor.subject(), "DOP", "BASIC"),
                "b-tbp-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tbp-" + tag, sourceActor);
  }

  @Test
  void serviceManagedWriteTransactionIsActiveAndReadCommitted() {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean active = new AtomicBoolean();
    AtomicReference<String> effectiveIsolation = new AtomicReference<>();
    AtomicInteger configuredIsolation = new AtomicInteger(-1);
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                active.set(TransactionSynchronizationManager.isActualTransactionActive());
                effectiveIsolation.set(
                    jdbc.queryForObject(
                        "select current_setting('transaction_isolation')", String.class));
                configuredIsolation.set(
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
              }
              return invocation.callRealMethod();
            })
        .when(authorization)
        .decideTransfer(any(), any());

    transfers.transfer(
        new TransferCommand(sourceId, destinationId, 40_00L, "DOP"),
        "tbp-isolation-" + tag,
        sourceActor);

    assertThat(active).isTrue();
    assertThat(effectiveIsolation.get()).isEqualTo("read committed");
    assertThat(configuredIsolation.get()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
  }

  @Test
  void deleteBeforeSecondReadRejectsWithoutEffects() throws Exception {
    UUID beneficiaryId = saveBeneficiary("tbp-before");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    String key = "tbp-before-use-" + tag;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 3) {
                entered.countDown();
                if (!gate.await(60, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("barrier gate timeout");
                }
              }
              return invocation.callRealMethod();
            })
        .when(resolver)
        .resolveForOwner(any(), any());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    try {
      Future<TransferResult> future =
          pool.submit(() -> transfers.transfer(command, key, sourceActor));
      assertThat(entered.await(60, TimeUnit.SECONDS)).isTrue();
      assertThat(beneficiaries.remove(beneficiaryId, sourceActor)).isTrue();
      gate.countDown();

      try {
        future.get(120, TimeUnit.SECONDS);
        assertThat(false).as("expected deterministic rejection").isTrue();
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(TransferOperationException.class);
        assertThat(((TransferOperationException) e.getCause()).getKind())
            .isEqualTo(TransferOperationException.Kind.REJECTED);
      }
    } finally {
      pool.shutdownNow();
    }

    Map<String, Object> row = intentRow(key);
    assertThat(row.get("outcome")).isEqualTo("REJECTED");
    assertThat(row.get("beneficiary_id")).isEqualTo(beneficiaryId);
    assertThat(row.get("path")).isEqualTo("beneficiary-transfer");
    assertThat(operationMovements(row.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  @Test
  void deleteAfterValidationConfirmsAndDeleteStands() throws Exception {
    UUID beneficiaryId = saveBeneficiary("tbp-after");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    String key = "tbp-after-use-" + tag;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 3) {
                Object result = invocation.callRealMethod();
                entered.countDown();
                if (!gate.await(60, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("barrier gate timeout");
                }
                return result;
              }
              return invocation.callRealMethod();
            })
        .when(resolver)
        .resolveForOwner(any(), any());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    TransferResult result;
    try {
      Future<TransferResult> future =
          pool.submit(() -> transfers.transfer(command, key, sourceActor));
      assertThat(entered.await(60, TimeUnit.SECONDS)).isTrue();
      assertThat(beneficiaries.remove(beneficiaryId, sourceActor)).isTrue();
      gate.countDown();
      result = future.get(120, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(result.created()).isTrue();
    assertThat(usageCount(result.operationId())).isEqualTo(1);
    assertThat(operationMovements(result.operationId())).isEqualTo(2);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
    assertThat(beneficiaries.findForOwner(beneficiaryId, sourceActor)).isEmpty();
  }

  @Test
  void oppositeResolvedPairsCompleteWithoutDeadlock() throws Exception {
    deposits.deposit(
        new DepositCommand(destinationId, 100_00L, "DOP"),
        "f-tbp-opposite-" + tag,
        destinationActor);
    UUID benA =
        beneficiaries
            .create(new BeneficiaryCommand(destinationId, "Hacia-B"), "tbp-a-" + tag, sourceActor)
            .beneficiaryId();
    UUID benB =
        beneficiaries
            .create(new BeneficiaryCommand(sourceId, "Hacia-A"), "tbp-b-" + tag, destinationActor)
            .beneficiaryId();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<TransferResult> first =
          pool.submit(
              () ->
                  transfers.transfer(
                      new TransferCommand(sourceId, null, benA, 60_00L, "DOP"),
                      "tbp-fw-" + tag,
                      sourceActor));
      Future<TransferResult> second =
          pool.submit(
              () ->
                  transfers.transfer(
                      new TransferCommand(destinationId, null, benB, 60_00L, "DOP"),
                      "tbp-bw-" + tag,
                      destinationActor));
      assertThat(first.get(120, TimeUnit.SECONDS).created()).isTrue();
      assertThat(second.get(120, TimeUnit.SECONDS).created()).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(100_00L);
  }

  @Test
  void parallelSameKeyConvergesToSingleEffect() throws Exception {
    UUID beneficiaryId = saveBeneficiary("tbp-samekey");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 10_00L, "DOP");
    String key = "tbp-samekey-use-" + tag;

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<TransferResult> first =
          pool.submit(() -> transfers.transfer(command, key, sourceActor));
      Future<TransferResult> second =
          pool.submit(() -> transfers.transfer(command, key, sourceActor));
      TransferResult r1 = first.get(120, TimeUnit.SECONDS);
      TransferResult r2 = second.get(120, TimeUnit.SECONDS);
      assertThat(List.of(r1.created(), r2.created()).stream().filter(c -> c).count()).isEqualTo(1);
      assertThat(r1.operationId()).isEqualTo(r2.operationId());
    } finally {
      pool.shutdownNow();
    }

    Map<String, Object> row = intentRow(key);
    assertThat(row.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(operationMovements(row.get("operation_id"))).isEqualTo(2);
    assertThat(usageCount(row.get("operation_id"))).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(90_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(10_00L);
  }

  @Test
  void directAndBeneficiarySameKeyConvergeToSingleEffect() throws Exception {
    UUID beneficiaryId = saveBeneficiary("tbp-cross");
    String key = "tbp-cross-use-" + tag;
    TransferCommand direct = new TransferCommand(sourceId, destinationId, 10_00L, "DOP");
    TransferCommand viaBeneficiary =
        new TransferCommand(sourceId, null, beneficiaryId, 10_00L, "DOP");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    TransferResult winner = null;
    int conflicts = 0;
    try {
      List<Future<TransferResult>> futures =
          List.of(
              pool.submit(() -> transfers.transfer(direct, key, sourceActor)),
              pool.submit(() -> transfers.transfer(viaBeneficiary, key, sourceActor)));
      for (Future<TransferResult> future : futures) {
        try {
          TransferResult result = future.get(120, TimeUnit.SECONDS);
          assertThat(result.created()).isTrue();
          winner = result;
        } catch (ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(TransferOperationException.class);
          assertThat(((TransferOperationException) e.getCause()).getKind())
              .isEqualTo(TransferOperationException.Kind.CONFLICT);
          conflicts++;
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(winner).isNotNull();
    assertThat(conflicts).isEqualTo(1);
    Map<String, Object> row = intentRow(key);
    assertThat(row.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(row.get("operation_id")).isEqualTo(winner.operationId());
    assertThat(operationMovements(winner.operationId())).isEqualTo(2);
    assertThat(balanceOf(sourceId)).isEqualTo(90_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(10_00L);
    if ("beneficiary-transfer".equals(row.get("path"))) {
      assertThat(usageCount(winner.operationId())).isEqualTo(1);
    } else {
      assertThat(usageCount(winner.operationId())).isZero();
    }
  }

  private UUID saveBeneficiary(String key) {
    return beneficiaries
        .create(new BeneficiaryCommand(destinationId, "Ahorros"), key, sourceActor)
        .beneficiaryId();
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

  private int usageCount(Object operationId) {
    Integer total =
        jdbc.queryForObject(
            "select count(*) from transfer_beneficiary_usage where operation_id = ?",
            Integer.class,
            operationId);
    return total == null ? 0 : total;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
