package com.bancoelcapital.postgres;

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
 * Real PostgreSQL concurrency proof: competing transfers serialize on the UUID-ordered account-row
 * write locks, so confirmed debits never exceed the funded source balance, opposite transfers never
 * deadlock, and same-key callers converge to a single financial effect. No sleeps: threads race
 * through a start gate and only invariants are asserted, never thread order. A missing future
 * result within the timeout fails the test, which is also the deadlock detector.
 */
class TransferPostgresConcurrencyTest extends AbstractPostgresTest {

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
    String sourceHolder = "holder-pgtc-src-" + tag;
    String destinationHolder = "holder-pgtc-dst-" + tag;
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-tts-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "c-ttd-" + tag, destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceHolder, "DOP", "BASIC"),
                "a-tts-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "a-ttd-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tts-" + tag, sourceActor);
    deposits.deposit(
        new DepositCommand(destinationId, 100_00L, "DOP"), "f-ttd-" + tag, destinationActor);
  }

  @Test
  void competingTransfersConfirmAtMostTheFundedBalance() throws Exception {
    // Source 10000, concurrent 7000 + 7000 from the same source: exactly one CONFIRMED and one
    // REJECTED for insufficient funds; final source 3000, destination 17000. Never negative.
    Outcome outcome =
        runParallel(
            List.of(
                new TransferCommand(sourceId, destinationId, 70_00L, "DOP"),
                new TransferCommand(sourceId, destinationId, 70_00L, "DOP")),
            List.of("tca-" + tag, "tcb-" + tag),
            List.of(sourceActor, sourceActor));

    assertThat(outcome.results().stream().filter(TransferResult::created).count()).isEqualTo(1);
    assertThat(outcome.failures()).hasSize(1).allMatch(TransferPostgresConcurrencyTest::isRejected);
    assertThat(confirmedTransferOperationCount()).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(30_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(170_00L);
  }

  @Test
  void oppositeTransfersCompleteWithoutDeadlock() throws Exception {
    Outcome outcome =
        runParallel(
            List.of(
                new TransferCommand(sourceId, destinationId, 60_00L, "DOP"),
                new TransferCommand(destinationId, sourceId, 60_00L, "DOP")),
            List.of("to1-" + tag, "to2-" + tag),
            List.of(sourceActor, destinationActor));

    assertThat(outcome.results().stream().filter(TransferResult::created).count()).isEqualTo(2);
    assertThat(outcome.failures()).isEmpty();
    assertThat(transferDebitCount(sourceId)).isEqualTo(1);
    assertThat(transferCreditCount(destinationId)).isEqualTo(1);
    assertThat(transferDebitCount(destinationId)).isEqualTo(1);
    assertThat(transferCreditCount(sourceId)).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(100_00L);
  }

  @Test
  void parallelSameKeyCreatesSingleFinancialEffect() throws Exception {
    String key = "tsk-" + tag;
    var command = new TransferCommand(sourceId, destinationId, 10_00L, "DOP");
    Outcome outcome =
        runParallel(
            List.of(command, command), List.of(key, key), List.of(sourceActor, sourceActor));

    assertThat(outcome.results()).hasSize(2);
    assertThat(outcome.results().stream().filter(TransferResult::created).count()).isEqualTo(1);
    assertThat(outcome.failures()).isEmpty();
    assertThat(confirmedTransferOperationCount()).isEqualTo(1);
    assertThat(transferDebitCount(sourceId)).isEqualTo(1);
    assertThat(transferCreditCount(destinationId)).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(90_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(110_00L);
  }

  @Test
  void manySmallTransfersAllConfirmWhenFundsCoverThem() throws Exception {
    List<TransferCommand> commands = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    List<AuthenticatedActor> actors = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      commands.add(new TransferCommand(sourceId, destinationId, 10_00L, "DOP"));
      keys.add("tm-" + tag + "-" + i);
      actors.add(sourceActor);
    }
    Outcome outcome = runParallel(commands, keys, actors);

    assertThat(outcome.results().stream().filter(TransferResult::created).count()).isEqualTo(8);
    assertThat(outcome.failures()).isEmpty();
    assertThat(confirmedTransferOperationCount()).isEqualTo(8);
    assertThat(balanceOf(sourceId)).isEqualTo(20_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(180_00L);
  }

  private Outcome runParallel(
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
          results.add(future.get(60, TimeUnit.SECONDS));
        } catch (Exception e) {
          failures.add(e.getCause() == null ? e : e.getCause());
        }
      }
      return new Outcome(results, failures);
    } finally {
      pool.shutdownNow();
    }
  }

  private static boolean isRejected(Throwable throwable) {
    return throwable instanceof TransferOperationException
        && ((TransferOperationException) throwable).getKind()
            == TransferOperationException.Kind.REJECTED;
  }

  private long confirmedTransferOperationCount() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from financial_operations where account_id = ?"
                + " and operation_type = 'TRANSFER' and status = 'CONFIRMED'",
            Long.class,
            sourceId);
    return count == null ? 0 : count;
  }

  private long transferDebitCount(UUID accountId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id = m.operation_id"
                + " where m.account_id = ? and m.direction = 'DEBIT' and o.operation_type ="
                + " 'TRANSFER'",
            Long.class,
            accountId);
    return count == null ? 0 : count;
  }

  private long transferCreditCount(UUID accountId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id = m.operation_id"
                + " where m.account_id = ? and m.direction = 'CREDIT' and o.operation_type ="
                + " 'TRANSFER'",
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
