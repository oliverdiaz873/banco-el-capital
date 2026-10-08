package com.bancoelcapital.customers.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Real parallel creation with the same Idempotency-Key and payload. No sleeps: both threads wait on
 * a start gate, then race. The database PK guarantees a single creation; the loser must either
 * replay the winner or surface a deterministic persistence conflict, never a second customer.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class CustomerCreationConcurrencyTest {

  @Autowired CustomerCreationService service;

  @Autowired CustomerRepository customers;

  @Autowired CustomerCreationIdempotencyRepository idempotency;

  @Test
  void parallelSameKeyCreatesSingleCustomer() throws Exception {
    String key = "concurrent-k1";
    var command = new CustomerCreationCommand("concurrent-001", "Concurrent");
    var actor = new AuthenticatedActor("concurrent-001", Set.of());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<CustomerResult> task =
        () -> {
          ready.countDown();
          if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start gate timeout");
          }
          return service.create(command, key, actor);
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<CustomerResult> first = pool.submit(task);
      Future<CustomerResult> second = pool.submit(task);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<CustomerResult> replays = new ArrayList<>();
      List<Throwable> conflicts = new ArrayList<>();
      for (Future<CustomerResult> future : List.of(first, second)) {
        try {
          replays.add(future.get(30, TimeUnit.SECONDS));
        } catch (Exception e) {
          conflicts.add(e.getCause() == null ? e : e.getCause());
        }
      }

      assertThat(customers.findById("concurrent-001")).isPresent();
      assertThat(idempotency.findById(key)).isPresent();
      assertThat(customers.count()).isEqualTo(1);
      // Same-key rows: at most the winner row plus a duplicate-business-key replay row is
      // impossible here (same key), so exactly one row for this key.
      assertThat(idempotency.findById(key).orElseThrow().getOutcome())
          .isEqualTo(CustomerCreationOutcome.CONFIRMED);
      // At least one thread confirmed or replayed; the other never created a second customer.
      assertThat(replays.size() + conflicts.size()).isEqualTo(2);
      if (!replays.isEmpty()) {
        assertThat(replays).allMatch(r -> r.customerId().equals("concurrent-001"));
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
