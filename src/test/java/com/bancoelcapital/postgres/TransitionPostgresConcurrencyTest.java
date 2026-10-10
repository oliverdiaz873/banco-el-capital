package com.bancoelcapital.postgres;

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
import org.springframework.jdbc.core.JdbcTemplate;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.accounts.internal.AccountTransition;
import com.bancoelcapital.accounts.internal.AccountTransitionService;
import com.bancoelcapital.accounts.internal.TransitionCommand;
import com.bancoelcapital.accounts.internal.TransitionException;
import com.bancoelcapital.accounts.internal.TransitionResult;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.financialops.withdrawal.WithdrawalCommand;
import com.bancoelcapital.financialops.withdrawal.WithdrawalOperationException;
import com.bancoelcapital.financialops.withdrawal.WithdrawalResult;
import com.bancoelcapital.financialops.withdrawal.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Real PostgreSQL concurrency proof for transitions: a block racing a debit serializes on the
 * account-row write lock, so no debit ever confirms on a committed BLOCKED row and no transition is
 * ever lost; same-key transitions converge to a single effect; unauthorized concurrent attempts
 * change nothing and learn nothing. No sleeps: threads race through a start gate and only
 * invariants are asserted, never thread order. A missing future result within the timeout fails the
 * test, which is also the deadlock detector.
 */
class TransitionPostgresConcurrencyTest extends AbstractPostgresTest {

  @Autowired AccountTransitionService transitions;

  @Autowired DepositService deposits;

  @Autowired WithdrawalService withdrawals;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor employee;
  AuthenticatedActor holderActor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-pgtc-" + tag;
    employee = new AuthenticatedActor("emp-" + tag, Set.of("BANK_EMPLOYEE"));
    holderActor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, holderActor);
    accountId =
        accounts
            .create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, holderActor)
            .accountId();
    deposits.deposit(new DepositCommand(accountId, 100_00L, "DOP"), "f-" + tag, holderActor);
  }

  @Test
  void blockRacingDebitNeverConfirmsDebitOnBlockedRow() throws Exception {
    // Either the block commits first (debit rejects on BLOCKED) or the debit commits first
    // (block confirms afterwards). Invariants in both orders: exactly one block effect, final
    // status BLOCKED, balance never negative.
    List<Object> outcomes =
        runParallel(
            List.of(
                () ->
                    transitions.transition(
                        new TransitionCommand(accountId, AccountTransition.BLOCK),
                        "tb-" + tag,
                        employee),
                () ->
                    withdrawals.withdraw(
                        new WithdrawalCommand(accountId, 40_00L, "DOP"),
                        "tw-" + tag,
                        holderActor)));

    assertThat(outcomes).hasSize(2);
    long changedBlocks =
        outcomes.stream()
            .filter(TransitionResult.class::isInstance)
            .map(TransitionResult.class::cast)
            .filter(TransitionResult::changed)
            .count();
    assertThat(changedBlocks).isEqualTo(1);
    long confirmedDebits = outcomes.stream().filter(WithdrawalResult.class::isInstance).count();
    long rejectedDebits =
        outcomes.stream()
            .filter(
                outcome ->
                    outcome instanceof WithdrawalOperationException withdrawal
                        && withdrawal.getKind() == WithdrawalOperationException.Kind.REJECTED)
            .count();
    assertThat(confirmedDebits + rejectedDebits).isEqualTo(1);
    assertThat(status()).isEqualTo("BLOCKED");
    assertThat(balance()).isIn(100_00L, 60_00L);
    assertThat(transitionIdempotencyCount("tb-" + tag)).isEqualTo(1);
  }

  @Test
  void parallelSameKeyTransitionsConvergeToSingleEffect() throws Exception {
    String key = "tsk-" + tag;
    var command = new TransitionCommand(accountId, AccountTransition.BLOCK);
    List<Object> outcomes =
        runParallel(
            List.of(
                () -> transitions.transition(command, key, employee),
                () -> transitions.transition(command, key, employee)));

    assertThat(outcomes).hasSize(2);
    assertThat(
            outcomes.stream()
                .filter(TransitionResult.class::isInstance)
                .map(TransitionResult.class::cast)
                .filter(TransitionResult::changed)
                .count())
        .isEqualTo(1);
    assertThat(outcomes).noneMatch(TransitionException.class::isInstance);
    assertThat(status()).isEqualTo("BLOCKED");
    assertThat(transitionIdempotencyCount(key)).isEqualTo(1);
  }

  @Test
  void concurrentUnauthorizedAttemptsChangeNothing() throws Exception {
    var stranger = new AuthenticatedActor("stranger-" + tag, Set.of());
    var command = new TransitionCommand(accountId, AccountTransition.BLOCK);
    List<Object> outcomes =
        runParallel(
            List.of(
                () -> transitions.transition(command, "tu1-" + tag, stranger),
                () -> transitions.transition(command, "tu2-" + tag, stranger)));

    assertThat(outcomes)
        .hasSize(2)
        .allMatch(
            outcome ->
                outcome instanceof TransitionException
                    && ((TransitionException) outcome).getKind()
                        == TransitionException.Kind.FORBIDDEN);
    assertThat(status()).isEqualTo("ACTIVE");
    assertThat(transitionIdempotencyCount("tu1-" + tag)).isZero();
    assertThat(transitionIdempotencyCount("tu2-" + tag)).isZero();
  }

  @Test
  void closeRacingFullWithdrawalNeverClosesFundedAccount() throws Exception {
    // Close requires zero balance under the same row lock: either the withdrawal empties the
    // account first (close confirms on zero) or the close sees funds and rejects. Invariant:
    // never (CLOSED with balance > 0).
    List<Object> outcomes =
        runParallel(
            List.of(
                () ->
                    transitions.transition(
                        new TransitionCommand(accountId, AccountTransition.CLOSE),
                        "tc-" + tag,
                        employee),
                () ->
                    withdrawals.withdraw(
                        new WithdrawalCommand(accountId, 100_00L, "DOP"),
                        "tw-full-" + tag,
                        holderActor)));

    assertThat(outcomes).hasSize(2);
    // The exact-balance withdrawal always confirms (no concurrent block here to stop it).
    assertThat(outcomes).filteredOn(WithdrawalResult.class::isInstance).hasSize(1);
    assertThat(balance()).isZero();
    if (status().equals("CLOSED")) {
      // Withdrawal emptied first: close confirmed on zero.
      assertThat(outcomes).filteredOn(TransitionResult.class::isInstance).hasSize(1);
    } else {
      assertThat(status()).isEqualTo("ACTIVE");
      assertThat(outcomes)
          .filteredOn(
              outcome ->
                  outcome instanceof TransitionException
                      && ((TransitionException) outcome).getKind()
                          == TransitionException.Kind.REJECTED)
          .hasSize(1);
    }
  }

  private List<Object> runParallel(List<Callable<Object>> tasks) throws Exception {
    int size = tasks.size();
    CountDownLatch ready = new CountDownLatch(size);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(size);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (Callable<Object> task : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate timeout");
                  }
                  try {
                    return task.call();
                  } catch (Exception e) {
                    return e;
                  }
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Object> outcomes = new ArrayList<>();
      for (Future<Object> future : futures) {
        outcomes.add(future.get(60, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  private int transitionIdempotencyCount(String key) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from account_transition_idempotency where idempotency_key = ?",
            Integer.class,
            key);
    return count == null ? 0 : count;
  }

  private String status() {
    return jdbc.queryForObject("select status from accounts where id = ?", String.class, accountId);
  }

  private Long balance() {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
