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

import com.bancoelcapital.accounts.AccountCreationCommand;
import com.bancoelcapital.accounts.AccountCreationService;
import com.bancoelcapital.customers.CustomerCreationCommand;
import com.bancoelcapital.customers.CustomerCreationService;
import com.bancoelcapital.financialops.DepositCommand;
import com.bancoelcapital.financialops.DepositService;
import com.bancoelcapital.financialops.WithdrawalCommand;
import com.bancoelcapital.financialops.WithdrawalOperationException;
import com.bancoelcapital.financialops.WithdrawalResult;
import com.bancoelcapital.financialops.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Real PostgreSQL concurrency proof: competing withdrawals serialize on the account-row write lock,
 * so confirmed debits never exceed the funded balance and same-key callers converge to a single
 * financial effect. No sleeps; threads race through a start gate and only invariants are asserted,
 * never thread order.
 */
class WithdrawalPostgresConcurrencyTest extends AbstractPostgresTest {

  @Autowired WithdrawalService withdrawals;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor actor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-pgcc-" + tag;
    actor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, actor);
    var created =
        accounts.create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, actor);
    accountId = created.accountId();
    deposits.deposit(new DepositCommand(accountId, 10_00L, "DOP"), "f-" + tag, actor);
  }

  @Test
  void competingWithdrawalsConfirmAtMostTheFundedBalance() throws Exception {
    // Balance 1000, concurrent 700 + 500: exactly one CONFIRMED and one REJECTED; either may win,
    // so the final balance is 300 or 500, never negative and never -200.
    List<WithdrawalResult> confirmed = new ArrayList<>();
    List<Throwable> rejected = new ArrayList<>();
    runParallel(
        List.of(
            new WithdrawalCommand(accountId, 7_00L, "DOP"),
            new WithdrawalCommand(accountId, 5_00L, "DOP")),
        List.of("wa-" + tag, "wb-" + tag),
        confirmed,
        rejected);

    assertThat(confirmed).hasSize(1);
    assertThat(rejected).hasSize(1).allMatch(WithdrawalPostgresConcurrencyTest::isRejected);
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
    assertThat(rejected).hasSize(3).allMatch(WithdrawalPostgresConcurrencyTest::isRejected);
    assertThat(balance()).isZero();
  }

  @Test
  void parallelSameKeyCreatesSingleFinancialEffect() throws Exception {
    String key = "wsk-" + tag;
    var command = new WithdrawalCommand(accountId, 1_00L, "DOP");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var first =
          pool.submit(
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("start gate timeout");
                }
                return withdrawals.withdraw(command, key, actor);
              });
      var second =
          pool.submit(
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("start gate timeout");
                }
                return withdrawals.withdraw(command, key, actor);
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<WithdrawalResult> results = new ArrayList<>();
      List<Throwable> failures = new ArrayList<>();
      for (Future<WithdrawalResult> future : List.of(first, second)) {
        try {
          results.add(future.get(60, TimeUnit.SECONDS));
        } catch (Exception e) {
          failures.add(e.getCause() == null ? e : e.getCause());
        }
      }

      assertThat(withdrawalOpCount()).isEqualTo(1);
      assertThat(debitCount()).isEqualTo(1);
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
          confirmed.add(future.get(60, TimeUnit.SECONDS));
        } catch (Exception e) {
          rejected.add(e.getCause() == null ? e : e.getCause());
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static boolean isRejected(Throwable throwable) {
    return throwable instanceof WithdrawalOperationException
        && ((WithdrawalOperationException) throwable).getKind()
            == WithdrawalOperationException.Kind.REJECTED;
  }

  private long withdrawalOpCount() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from financial_operations where account_id = ?"
                + " and operation_type = 'WITHDRAWAL'",
            Long.class,
            accountId);
    return count == null ? 0 : count;
  }

  private long debitCount() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from movements where account_id = ? and direction = 'DEBIT'",
            Long.class,
            accountId);
    return count == null ? 0 : count;
  }

  private Long balance() {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
