package com.bancoelcapital.financialops;

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

import com.bancoelcapital.accounts.AccountCreationCommand;
import com.bancoelcapital.accounts.AccountCreationService;
import com.bancoelcapital.customers.CustomerCreationCommand;
import com.bancoelcapital.customers.CustomerCreationService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Real parallel deposits with the same Idempotency-Key and payload. No sleeps: both threads wait on
 * a start gate, then race. The idempotency PK guarantees exactly one financial effect; the loser
 * replays the winner. Final stored balance must reconcile with confirmed movements.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:deposit-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class DepositConcurrencyTest {

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired FinancialOperationRepository operations;

  @Autowired MovementRepository movements;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor actor = new AuthenticatedActor("holder-conc", Set.of());

  @BeforeEach
  void setup() {
    customers.create(new CustomerCreationCommand("holder-conc", null), "c-conc", actor);
    var created =
        accounts.create(new AccountCreationCommand("holder-conc", "DOP", "BASIC"), "a-conc", actor);
    accountId = created.accountId();
  }

  @Test
  void parallelSameKeyCreatesSingleFinancialEffect() throws Exception {
    String key = "d-conc-1";
    var command = new DepositCommand(accountId, 10_00L, "DOP");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<DepositResult> task =
        () -> {
          ready.countDown();
          if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start gate timeout");
          }
          return deposits.deposit(command, key, actor);
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<DepositResult> first = pool.submit(task);
      Future<DepositResult> second = pool.submit(task);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<DepositResult> results = new ArrayList<>();
      List<Throwable> failures = new ArrayList<>();
      for (Future<DepositResult> future : List.of(first, second)) {
        try {
          results.add(future.get(30, TimeUnit.SECONDS));
        } catch (Exception e) {
          failures.add(e.getCause() == null ? e : e.getCause());
        }
      }

      assertThat(operations.count()).isEqualTo(1);
      assertThat(movements.count()).isEqualTo(1);
      assertThat(results.size() + failures.size()).isEqualTo(2);
      if (!results.isEmpty()) {
        assertThat(results).allMatch(r -> r.operationId() != null);
      }
      Long balance =
          jdbc.queryForObject(
              "select balance_minor_units from accounts where id = ?", Long.class, accountId);
      assertThat(balance).isEqualTo(10_00L);
    } finally {
      pool.shutdownNow();
    }
  }
}
