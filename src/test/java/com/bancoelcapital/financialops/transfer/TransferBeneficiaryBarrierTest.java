package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.beneficiaries.api.BeneficiaryResolver;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryCommand;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * E5 deterministic beneficiary-deletion barriers on H2 (PostgreSQL proof remains mandatory and
 * lives in {@code TransferBeneficiaryPostgresBarrierTest}). No sleeps: the transfer thread blocks
 * on latches inside spied collaborators while the main thread deletes, then releases. Timeouts fail
 * the test and double as deadlock detectors. No {@code @Transactional}: each service call commits
 * so worker threads observe committed state; tags isolate tests sharing one H2 mem database.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-ben-barrier;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferBeneficiaryBarrierTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired BeneficiaryService beneficiaries;

  @Autowired JdbcTemplate jdbc;

  @MockitoSpyBean BeneficiaryResolver resolver;

  String tag;
  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    sourceActor = new AuthenticatedActor("holder-tbb-src-" + tag, Set.of());
    destinationActor = new AuthenticatedActor("holder-tbb-dst-" + tag, Set.of());
    customers.create(
        new CustomerCreationCommand(sourceActor.subject(), null), "c-tbb-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationActor.subject(), null),
        "d-tbb-" + tag,
        destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-tbb-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationActor.subject(), "DOP", "BASIC"),
                "b-tbb-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tbb-" + tag, sourceActor);
  }

  @Test
  void deleteBeforeSecondReadRejectsWithoutEffects() throws Exception {
    UUID beneficiaryId = saveBeneficiary("tbb-before");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    String key = "tbb-before-use-" + tag;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                entered.countDown();
                if (!gate.await(30, TimeUnit.SECONDS)) {
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
      assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(beneficiaries.remove(beneficiaryId, sourceActor)).isTrue();
      gate.countDown();

      try {
        future.get(60, TimeUnit.SECONDS);
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
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_REJECTED'"
                    + " and resource_id = ?",
                key))
        .isEqualTo(1);
  }

  @Test
  void deleteAfterValidationConfirmsAndDeleteStands() throws Exception {
    UUID beneficiaryId = saveBeneficiary("tbb-after");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    String key = "tbb-after-use-" + tag;
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                Object result = invocation.callRealMethod();
                entered.countDown();
                if (!gate.await(30, TimeUnit.SECONDS)) {
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
      assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(beneficiaries.remove(beneficiaryId, sourceActor)).isTrue();
      gate.countDown();
      result = future.get(60, TimeUnit.SECONDS);
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
        "f-tbb-opposite-" + tag,
        destinationActor);
    UUID benA =
        beneficiaries
            .create(new BeneficiaryCommand(destinationId, "Hacia-B"), "tbb-a-" + tag, sourceActor)
            .beneficiaryId();
    UUID benB =
        beneficiaries
            .create(new BeneficiaryCommand(sourceId, "Hacia-A"), "tbb-b-" + tag, destinationActor)
            .beneficiaryId();
    TransferCommand forward = new TransferCommand(sourceId, null, benA, 60_00L, "DOP");
    TransferCommand backward = new TransferCommand(destinationId, null, benB, 60_00L, "DOP");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<TransferResult> first =
          pool.submit(() -> transfers.transfer(forward, "tbb-fw-" + tag, sourceActor));
      Future<TransferResult> second =
          pool.submit(() -> transfers.transfer(backward, "tbb-bw-" + tag, destinationActor));
      TransferResult r1 = first.get(60, TimeUnit.SECONDS);
      TransferResult r2 = second.get(60, TimeUnit.SECONDS);
      assertThat(r1.created()).isTrue();
      assertThat(r2.created()).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(100_00L);
  }

  @Test
  void parallelSameKeyConvergesToSingleEffect() throws Exception {
    UUID beneficiaryId = saveBeneficiary("tbb-samekey");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 10_00L, "DOP");
    String key = "tbb-samekey-use-" + tag;

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<TransferResult> first =
          pool.submit(() -> transfers.transfer(command, key, sourceActor));
      Future<TransferResult> second =
          pool.submit(() -> transfers.transfer(command, key, sourceActor));
      TransferResult r1 = first.get(60, TimeUnit.SECONDS);
      TransferResult r2 = second.get(60, TimeUnit.SECONDS);
      assertThat(List.of(r1.created(), r2.created()).stream().filter(created -> created).count())
          .isEqualTo(1);
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
    UUID beneficiaryId = saveBeneficiary("tbb-cross");
    String key = "tbb-cross-use-" + tag;
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
          TransferResult result = future.get(60, TimeUnit.SECONDS);
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

  @Test
  void competingTransfersConfirmOnlyThoseCoveredByFunds() throws Exception {

    UUID beneficiaryId = saveBeneficiary("tbb-funds");
    TransferCommand first = new TransferCommand(sourceId, null, beneficiaryId, 70_00L, "DOP");
    TransferCommand second = new TransferCommand(sourceId, null, beneficiaryId, 70_00L, "DOP");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<String> outcomes = new java.util.ArrayList<>();
    try {
      Future<TransferResult> f1 =
          pool.submit(() -> transfers.transfer(first, "tbb-f1-" + tag, sourceActor));
      Future<TransferResult> f2 =
          pool.submit(() -> transfers.transfer(second, "tbb-f2-" + tag, sourceActor));
      outcomes.add(outcomeOf(f1));
      outcomes.add(outcomeOf(f2));
    } finally {
      pool.shutdownNow();
    }

    assertThat(outcomes.stream().filter("CONFIRMED"::equals).count()).isEqualTo(1);
    assertThat(outcomes.stream().filter("REJECTED"::equals).count()).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isGreaterThanOrEqualTo(0L);
    assertThat(balanceOf(sourceId) + balanceOf(destinationId)).isEqualTo(100_00L);
  }

  private String outcomeOf(Future<TransferResult> future) throws Exception {
    try {
      future.get(60, TimeUnit.SECONDS);
      return "CONFIRMED";
    } catch (ExecutionException e) {
      if (e.getCause() instanceof TransferOperationException toe
          && toe.getKind() == TransferOperationException.Kind.REJECTED) {
        return "REJECTED";
      }
      throw e;
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

  private int count(String sql, Object... args) {
    Integer total = jdbc.queryForObject(sql, Integer.class, args);
    return total == null ? 0 : total;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
