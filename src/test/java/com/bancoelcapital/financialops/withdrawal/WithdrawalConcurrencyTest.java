package com.bancoelcapital.financialops.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import com.bancoelcapital.financialops.core.FinancialOperationRepository;
import com.bancoelcapital.financialops.core.MovementRepository;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Real parallel withdrawals on H2 (PG validation remains a follow-up). No sleeps: threads wait on a
 * start gate, then race. The account-row write lock serializes debits so competing withdrawals
 * never overspend: at most the funded balance confirms, the rest reject deterministically.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:withdrawal-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class WithdrawalConcurrencyTest {

  @Autowired WithdrawalService withdrawals;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired FinancialOperationRepository operations;

  @Autowired MovementRepository movements;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor actor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-wc-" + tag;
    actor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, actor);
    var created =
        accounts.create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, actor);
    accountId = created.accountId();
    deposits.deposit(new DepositCommand(accountId, 10_00L, "DOP"), "f-" + tag, actor);
  }

  @Test
  void competingWithdrawalsConfirmAtMostTheFundedBalance() throws Exception {
    // Balance 1000, concurrent 700 + 500: exactly one CONFIRMED, one REJECTED. Either may win
    // the race, so the final balance is 300 (700 won) or 500 (500 won); never negative.
    List<WithdrawalResult> confirmed = new ArrayList<>();
    List<Throwable> rejected = new ArrayList<>();
    runParallel(
        List.of(
            new WithdrawalCommand(accountId, 7_00L, "DOP"),
            new WithdrawalCommand(accountId, 5_00L, "DOP")),
        List.of("w1-" + tag, "w2-" + tag),
        confirmed,
        rejected);

    assertThat(confirmed).hasSize(1);
    assertThat(rejected)
        .hasSize(1)
        .allMatch(
            t ->
                t instanceof WithdrawalOperationException
                    && ((WithdrawalOperationException) t).getKind()
                        == WithdrawalOperationException.Kind.REJECTED);
    assertThat(balance()).isIn(3_00L, 5_00L);
  }

  @Test
  void manySmallWithdrawalsAllConfirmWhenFundsCoverThem() throws Exception {
    // Balance 1000, eight concurrent 100: all confirm, final 200.
    List<WithdrawalCommand> commands = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      commands.add(new WithdrawalCommand(accountId, 1_00L, "DOP"));
      keys.add("ws-" + tag + "-" + i);
    }
    List<WithdrawalResult> confirmed = new ArrayList<>();
    List<Throwable> rejected = new ArrayList<>();
    runParallel(commands, keys, confirmed, rejected);

    assertThat(confirmed).hasSize(8);
    assertThat(rejected).isEmpty();
    assertThat(balance()).isEqualTo(2_00L);
  }

  @Test
  void manyLargeWithdrawalsConfirmExactlyThoseCoveredByFunds() throws Exception {
    // Balance 1000, eight concurrent 200: exactly five CONFIRMED, three REJECTED, final 0.
    List<WithdrawalCommand> commands = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      commands.add(new WithdrawalCommand(accountId, 2_00L, "DOP"));
      keys.add("wl-" + tag + "-" + i);
    }
    List<WithdrawalResult> confirmed = new ArrayList<>();
    List<Throwable> rejected = new ArrayList<>();
    runParallel(commands, keys, confirmed, rejected);

    assertThat(confirmed).hasSize(5);
    assertThat(rejected)
        .hasSize(3)
        .allMatch(
            t ->
                t instanceof WithdrawalOperationException
                    && ((WithdrawalOperationException) t).getKind()
                        == WithdrawalOperationException.Kind.REJECTED);
    assertThat(balance()).isZero();
  }

  @Test
  void parallelSameKeyCreatesSingleFinancialEffect() throws Exception {
    String key = "wsk-" + tag;
    var command = new WithdrawalCommand(accountId, 1_00L, "DOP");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<WithdrawalResult> task =
        () -> {
          ready.countDown();
          if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start gate timeout");
          }
          return withdrawals.withdraw(command, key, actor);
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<WithdrawalResult> first = pool.submit(task);
      Future<WithdrawalResult> second = pool.submit(task);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<WithdrawalResult> results = new ArrayList<>();
      List<Throwable> failures = new ArrayList<>();
      for (Future<WithdrawalResult> future : List.of(first, second)) {
        try {
          results.add(future.get(30, TimeUnit.SECONDS));
        } catch (Exception e) {
          failures.add(e.getCause() == null ? e : e.getCause());
        }
      }

      long withdrawalOps =
          jdbc.queryForObject(
              "select count(*) from financial_operations where account_id = ? and operation_type = 'WITHDRAWAL'",
              Long.class,
              accountId);
      long debitMovements =
          jdbc.queryForObject(
              "select count(*) from movements where account_id = ? and direction = 'DEBIT'",
              Long.class,
              accountId);
      assertThat(withdrawalOps).isEqualTo(1);
      assertThat(debitMovements).isEqualTo(1);
      assertThat(results.size() + failures.size()).isEqualTo(2);
      assertThat(balance()).isEqualTo(9_00L);
    } finally {
      pool.shutdownNow();
    }
  }

  private void runParallel(
      List<WithdrawalCommand> commands,
      List<String> keys,
      List<WithdrawalResult> confirmed,
      List<Throwable> rejected)
      throws Exception {
    int size = commands.size();
    CountDownLatch ready = new CountDownLatch(size);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(size);
    try {
      List<Future<WithdrawalResult>> futures = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        final WithdrawalCommand command = commands.get(i);
        final String key = keys.get(i);
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate timeout");
                  }
                  return withdrawals.withdraw(command, key, actor);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<WithdrawalResult> future : futures) {
        try {
          confirmed.add(future.get(30, TimeUnit.SECONDS));
        } catch (Exception e) {
          rejected.add(e.getCause() == null ? e : e.getCause());
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private Long balance() {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
