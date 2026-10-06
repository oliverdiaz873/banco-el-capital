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
 * Parallel deposits with distinct keys against one account. Each intent must confirm independently
 * with no lost updates: N operations, N movements, and stored balance equal to the initial balance
 * plus the sum of all deposits. H2 only; this proves the shared balance serializes correctly but
 * does not replace PostgreSQL validation.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:deposit-parallel;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class DepositParallelKeysTest {

  static final int DEPOSITS = 8;
  static final long AMOUNT = 10_00L;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor actor = new AuthenticatedActor("holder-par", Set.of());

  @BeforeEach
  void setup() {
    customers.create(new CustomerCreationCommand("holder-par", null), "c-par", actor);
    var created =
        accounts.create(new AccountCreationCommand("holder-par", "DOP", "BASIC"), "a-par", actor);
    accountId = created.accountId();
  }

  @Test
  void parallelDistinctKeysConfirmWithoutLostUpdates() throws Exception {
    CountDownLatch ready = new CountDownLatch(DEPOSITS);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(DEPOSITS);
    try {
      List<Future<DepositResult>> futures = new ArrayList<>();
      for (int i = 0; i < DEPOSITS; i++) {
        final String key = "par-" + i;
        Callable<DepositResult> task =
            () -> {
              ready.countDown();
              if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start gate timeout");
              }
              return deposits.deposit(new DepositCommand(accountId, AMOUNT, "DOP"), key, actor);
            };
        futures.add(pool.submit(task));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<DepositResult> results = new ArrayList<>();
      for (Future<DepositResult> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }

      assertThat(results).hasSize(DEPOSITS);
      assertThat(results).allMatch(DepositResult::created);
      assertThat(results.stream().map(DepositResult::operationId).distinct()).hasSize(DEPOSITS);
      assertThat(count("select count(*) from financial_operations")).isEqualTo(DEPOSITS);
      assertThat(count("select count(*) from movements")).isEqualTo(DEPOSITS);
      Long balance =
          jdbc.queryForObject(
              "select balance_minor_units from accounts where id = ?", Long.class, accountId);
      assertThat(balance).isEqualTo(DEPOSITS * AMOUNT);
    } finally {
      pool.shutdownNow();
    }
  }

  private int count(String sql) {
    Integer count = jdbc.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }
}
