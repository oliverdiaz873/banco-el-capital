package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.PersistenceException;

/**
 * Focused persistence proof for the assigned-identifier idempotency entity. A constructed record
 * must be inserted, never merged over an existing row, and a loaded record must report itself as
 * existing so future save semantics cannot be confused with insertion.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-idempotency-insert-only;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferOperationIdempotencyInsertOnlyTest {

  @Autowired TransferOperationIdempotencyRepository idempotency;

  @Autowired JdbcTemplate jdbc;

  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void constructedExistingKeyCannotBeMergedOverTheStoredRow() {
    String key = "insert-only-" + UUID.randomUUID();
    String originalHash = "a".repeat(64);
    String replacementHash = "b".repeat(64);
    UUID originalSource = UUID.randomUUID();
    TransferOperationIdempotency original =
        new TransferOperationIdempotency(
            key,
            UUID.randomUUID(),
            originalHash,
            TransferOperationOutcome.REJECTED,
            originalSource,
            null,
            "direct",
            100_00L,
            "DOP");
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    tx.executeWithoutResult(status -> idempotency.save(original));
    assertThat(original.isNew()).isFalse();

    TransferOperationIdempotency replacement =
        new TransferOperationIdempotency(
            key,
            UUID.randomUUID(),
            replacementHash,
            TransferOperationOutcome.CONFIRMED,
            UUID.randomUUID(),
            null,
            "direct",
            200_00L,
            "DOP");
    assertThat(replacement.isNew()).isTrue();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      idempotency.save(replacement);
                      idempotency.flush();
                    }))
        .isInstanceOfAny(
            DataIntegrityViolationException.class,
            EntityExistsException.class,
            PersistenceException.class);

    Map<String, Object> stored =
        jdbc.queryForMap(
            "select operation_id, outcome, request_hash, source_account_id, beneficiary_id, path,"
                + " amount_minor_units, currency from transfer_operation_idempotency"
                + " where idempotency_key = ?",
            key);
    assertThat(stored.get("outcome")).isEqualTo("REJECTED");
    assertThat(stored.get("request_hash")).isEqualTo(originalHash);
    assertThat(stored.get("source_account_id")).isEqualTo(originalSource);
    assertThat(stored.get("beneficiary_id")).isNull();
    assertThat(stored.get("path")).isEqualTo("direct");
    assertThat(stored.get("amount_minor_units")).isEqualTo(100_00L);
    assertThat(stored.get("currency")).isEqualTo("DOP");

    TransferOperationIdempotency loaded =
        tx.execute(status -> idempotency.findById(key)).orElseThrow();
    assertThat(loaded.isNew()).isFalse();
    assertThat(loaded.getRequestHash()).isEqualTo(originalHash);
  }
}
