package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Real parallel transfers on H2 (PG validation remains a follow-up). No sleeps: threads wait on a
 * start gate, then race. Both account rows are locked in UUID ascending order, so opposite
 * transfers cannot deadlock; the source-row serialization keeps competing transfers from
 * overspending. A missing future result within the timeout fails the test, which is also the
 * deadlock detector.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferConcurrencyTest {

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
    // Fresh holders and accounts per test: the Spring context (and H2 mem db) is shared across
    // test methods in the class, so fixed ids would accumulate balance from previous tests.
    tag = UUID.randomUUID().toString().substring(0, 8);
    String sourceHolder = "holder-tsrc-" + tag;
    String destinationHolder = "holder-tdst-" + tag;
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-tsrc-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "c-tdst-" + tag, destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceHolder, "DOP", "BASIC"),
                "a-tsrc-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "a-tdst-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tsrc-" + tag, sourceActor);
    deposits.deposit(
        new DepositCommand(destinationId, 100_00L, "DOP"), "f-tdst-" + tag, destinationActor);
  }

  @Test
  void parallelSameKeyConvergesToSingleEffect() throws Exception {
    var command = new TransferCommand(sourceId, destinationId, 10_00L, "DOP");
    String key = "tsk-" + tag;
    Outcome outcome = runParallel(List.of(command, command), List.of(key, key));

    assertThat(outcome.results()).hasSize(2);
    assertThat(outcome.results().stream().filter(TransferResult::created).count()).isEqualTo(1);
    assertThat(outcome.failures()).isEmpty();
    assertThat(transferOperationCount()).isEqualTo(1);
    assertThat(debitCount(sourceId)).isEqualTo(1);
    assertThat(creditCount(destinationId)).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(90_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(110_00L);
  }

  @Test
  void sameKeyDifferentPayloadConflictsWithoutRawPersistenceError() throws Exception {
    String key = "tkc-" + tag;
    Outcome outcome =
        runParallel(
            List.of(
                new TransferCommand(sourceId, destinationId, 10_00L, "DOP"),
                new TransferCommand(sourceId, destinationId, 20_00L, "DOP")),
            List.of(key, key));

    assertThat(outcome.results().stream().filter(TransferResult::created).count()).isEqualTo(1);
    assertThat(outcome.failures())
        .hasSize(1)
        .allMatch(
            t ->
                t instanceof TransferOperationException
                    && ((TransferOperationException) t).getKind()
                        == TransferOperationException.Kind.CONFLICT);
    assertThat(transferOperationCount()).isEqualTo(1);
    // Either amount may have won the race; balances always reconcile with the winner.
    assertThat(balanceOf(sourceId)).isIn(90_00L, 80_00L);
    assertThat(balanceOf(destinationId)).isIn(110_00L, 120_00L);
  }

  @Test
  void oppositeTransfersCompleteWithoutDeadlock() throws Exception {
    Outcome outcome =
        runParallelActors(
            List.of(
                new TransferCommand(sourceId, destinationId, 60_00L, "DOP"),
                new TransferCommand(destinationId, sourceId, 60_00L, "DOP")),
            List.of("to1-" + tag, "to2-" + tag),
            List.of(sourceActor, destinationActor));

    assertThat(outcome.results().stream().filter(TransferResult::created).count()).isEqualTo(2);
    assertThat(outcome.failures()).isEmpty();
    assertThat(debitCount(sourceId)).isEqualTo(1);
    assertThat(creditCount(destinationId)).isEqualTo(1);
    assertThat(debitCount(destinationId)).isEqualTo(1);
    assertThat(creditCount(sourceId)).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(100_00L);
  }

  @Test
  void competingTransfersConfirmOnlyThoseCoveredByFunds() throws Exception {
    // Source 10000, concurrent 7000 + 7000: exactly one CONFIRMED, one REJECTED for
    // insufficient funds; final source 3000, destination 17000. Never negative.
    Outcome outcome =
        runParallel(
            List.of(
                new TransferCommand(sourceId, destinationId, 70_00L, "DOP"),
                new TransferCommand(sourceId, destinationId, 70_00L, "DOP")),
            List.of("tc1-" + tag, "tc2-" + tag));

    assertThat(outcome.results().stream().filter(TransferResult::created).count()).isEqualTo(1);
    assertThat(outcome.failures())
        .hasSize(1)
        .allMatch(
            t ->
                t instanceof TransferOperationException
                    && ((TransferOperationException) t).getKind()
                        == TransferOperationException.Kind.REJECTED);
    assertThat(balanceOf(sourceId)).isEqualTo(30_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(170_00L);
    // One CONFIRMED plus one persisted REJECTED (rejections leave evidence, never effects).
    assertThat(transferOperationCount()).isEqualTo(2);
    assertThat(confirmedTransferOperationCount()).isEqualTo(1);
  }

  @Test
  void parallelDistinctKeysAllConfirmWhenFundsCoverThem() throws Exception {
    List<TransferCommand> commands = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      commands.add(new TransferCommand(sourceId, destinationId, 10_00L, "DOP"));
      keys.add("tm-" + tag + "-" + i);
    }
    Outcome outcome = runParallel(commands, keys);

    assertThat(outcome.results().stream().filter(TransferResult::created).count()).isEqualTo(4);
    assertThat(outcome.failures()).isEmpty();
    assertThat(transferOperationCount()).isEqualTo(4);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(140_00L);
  }

  private Outcome runParallel(List<TransferCommand> commands, List<String> keys) throws Exception {
    return runParallelActors(
        commands, keys, commands.stream().map(ignored -> sourceActor).toList());
  }

  private Outcome runParallelActors(
      List<TransferCommand> commands, List<String> keys, List<AuthenticatedActor> actors)
      throws Exception {
    int size = commands.size();
    CountDownLatch ready = new CountDownLatch(size);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(size);
    try {
      List<Future<TransferResult>> futures = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        final TransferCommand command = commands.get(i);
        final String key = keys.get(i);
        final AuthenticatedActor actor = actors.get(i);
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate timeout");
                  }
                  return transfers.transfer(command, key, actor);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<TransferResult> results = new ArrayList<>();
      List<Throwable> failures = new ArrayList<>();
      for (Future<TransferResult> future : futures) {
        try {
          results.add(future.get(30, TimeUnit.SECONDS));
        } catch (Exception e) {
          failures.add(e.getCause() == null ? e : e.getCause());
        }
      }
      return new Outcome(results, failures);
    } finally {
      pool.shutdownNow();
    }
  }

  private long transferOperationCount() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from financial_operations where account_id = ? and operation_type = 'TRANSFER'",
            Long.class,
            sourceId);
    return count == null ? 0 : count;
  }

  private long confirmedTransferOperationCount() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from financial_operations where account_id = ? and operation_type = 'TRANSFER' and status = 'CONFIRMED'",
            Long.class,
            sourceId);
    return count == null ? 0 : count;
  }

  private long debitCount(UUID accountId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id = m.operation_id"
                + " where m.account_id = ? and m.direction = 'DEBIT' and o.operation_type = 'TRANSFER'",
            Long.class,
            accountId);
    return count == null ? 0 : count;
  }

  private long creditCount(UUID accountId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id = m.operation_id"
                + " where m.account_id = ? and m.direction = 'CREDIT' and o.operation_type = 'TRANSFER'",
            Long.class,
            accountId);
    return count == null ? 0 : count;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }

  private record Outcome(List<TransferResult> results, List<Throwable> failures) {}
}
