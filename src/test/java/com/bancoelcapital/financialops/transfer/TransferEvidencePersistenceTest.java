package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import jakarta.persistence.PersistenceException;

/**
 * Transfer beneficiary evidence persistence (ADR-21, Phase 2). Proves the V11-equivalent shape on
 * H2: historical all-NULL rows persist, complete coherent rows persist per path, partial and
 * incoherent rows are rejected by real CHECK constraints on flush, and the usage table carries no
 * physical FK. Java invariants are proven without persistence; database invariants are proven only
 * by persist-plus-flush, never by construction alone.
 */
@DataJpaTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-evidence;MODE=PostgreSQL",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferEvidencePersistenceTest {

  @Autowired TestEntityManager entities;

  @Autowired TransferBeneficiaryUsageRepository usage;

  @Test
  void historicalAllNullRowPersists() {
    entities
        .getEntityManager()
        .createNativeQuery(
            "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                + " request_hash, outcome, created_at, source_account_id, beneficiary_id,"
                + " path, amount_minor_units, currency) values ('k-hist', NULL, 'hash',"
                + " 'REJECTED', now(), NULL, NULL, NULL, NULL, NULL)")
        .executeUpdate();
    entities.flush();
    entities.clear();

    TransferOperationIdempotency found =
        entities.find(TransferOperationIdempotency.class, "k-hist");
    assertThat(found.getSourceAccountId()).isNull();
    assertThat(found.getBeneficiaryId()).isNull();
    assertThat(found.getPath()).isNull();
    assertThat(found.getAmountMinorUnits()).isNull();
    assertThat(found.getCurrency()).isNull();
  }

  @Test
  void newCompleteDirectRowPersists() {
    UUID source = UUID.randomUUID();
    TransferOperationIdempotency stored =
        new TransferOperationIdempotency(
            "k-direct",
            UUID.randomUUID(),
            "hash",
            TransferOperationOutcome.CONFIRMED,
            source,
            null,
            "direct",
            100L,
            "DOP");
    entities.persist(stored);
    entities.flush();
    entities.clear();

    TransferOperationIdempotency found =
        entities.find(TransferOperationIdempotency.class, "k-direct");
    assertThat(found.getSourceAccountId()).isEqualTo(source);
    assertThat(found.getBeneficiaryId()).isNull();
    assertThat(found.getPath()).isEqualTo("direct");
  }

  @Test
  void newCompleteBeneficiaryRowPersists() {
    UUID beneficiary = UUID.randomUUID();
    TransferOperationIdempotency stored =
        new TransferOperationIdempotency(
            "k-benef",
            UUID.randomUUID(),
            "hash",
            TransferOperationOutcome.CONFIRMED,
            UUID.randomUUID(),
            beneficiary,
            "beneficiary-transfer",
            100L,
            "DOP");
    entities.persist(stored);
    entities.flush();
    entities.clear();

    TransferOperationIdempotency found =
        entities.find(TransferOperationIdempotency.class, "k-benef");
    assertThat(found.getBeneficiaryId()).isEqualTo(beneficiary);
    assertThat(found.getPath()).isEqualTo("beneficiary-transfer");
  }

  @Test
  void javaInvariantRejectsIncoherentIntent() {
    UUID source = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new TransferOperationIdempotency(
                    "k",
                    UUID.randomUUID(),
                    "hash",
                    TransferOperationOutcome.REJECTED,
                    source,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TransferOperationIdempotency(
                    "k",
                    UUID.randomUUID(),
                    "hash",
                    TransferOperationOutcome.REJECTED,
                    source,
                    UUID.randomUUID(),
                    "direct",
                    100L,
                    "DOP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TransferOperationIdempotency(
                    "k",
                    UUID.randomUUID(),
                    "hash",
                    TransferOperationOutcome.REJECTED,
                    source,
                    null,
                    "beneficiary-transfer",
                    100L,
                    "DOP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TransferOperationIdempotency(
                    "k",
                    UUID.randomUUID(),
                    "hash",
                    TransferOperationOutcome.REJECTED,
                    source,
                    null,
                    "other",
                    100L,
                    "DOP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TransferOperationIdempotency(
                    "k",
                    UUID.randomUUID(),
                    "hash",
                    TransferOperationOutcome.REJECTED,
                    null,
                    null,
                    "direct",
                    100L,
                    "DOP"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void checkConstraintsPresentInH2Schema() {
    List<String> clauses =
        (List<String>)
            entities
                .getEntityManager()
                .createNativeQuery(
                    "select cc.CHECK_CLAUSE from INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc"
                        + " join INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                        + " on tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME"
                        + " and tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA"
                        + " where tc.TABLE_NAME = 'TRANSFER_OPERATION_IDEMPOTENCY'",
                    String.class)
                .getResultList();

    assertThat(clauses).hasSizeGreaterThanOrEqualTo(3);
    String combined = String.join(" | ", clauses).toLowerCase();
    assertThat(combined).contains("beneficiary_id");
    assertThat(combined).contains("path");
    assertThat(combined).contains("amount_minor_units");
  }

  @Test
  void dbCheckRejectsPartialRow() {
    assertThatThrownBy(
            () ->
                entities
                    .getEntityManager()
                    .createNativeQuery(
                        "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                            + " request_hash, outcome, created_at, source_account_id, beneficiary_id,"
                            + " path, amount_minor_units, currency) values ('k-partial', NULL, 'h',"
                            + " 'REJECTED', now(), '"
                            + UUID.randomUUID()
                            + "', NULL, NULL, 100, 'DOP')")
                    .executeUpdate())
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("heck constraint");
  }

  @Test
  void dbCheckRejectsDirectWithBeneficiaryId() {
    assertThatThrownBy(
            () ->
                entities
                    .getEntityManager()
                    .createNativeQuery(
                        "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                            + " request_hash, outcome, created_at, source_account_id, beneficiary_id,"
                            + " path, amount_minor_units, currency) values ('k-direct-b', NULL, 'h',"
                            + " 'REJECTED', now(), '"
                            + UUID.randomUUID()
                            + "', '"
                            + UUID.randomUUID()
                            + "', 'direct', 100, 'DOP')")
                    .executeUpdate())
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("heck constraint");
  }

  @Test
  void dbCheckRejectsBeneficiaryWithoutId() {
    assertThatThrownBy(
            () ->
                entities
                    .getEntityManager()
                    .createNativeQuery(
                        "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                            + " request_hash, outcome, created_at, source_account_id, beneficiary_id,"
                            + " path, amount_minor_units, currency) values ('k-benef-noid', NULL, 'h',"
                            + " 'REJECTED', now(), '"
                            + UUID.randomUUID()
                            + "', NULL, 'beneficiary-transfer', 100, 'DOP')")
                    .executeUpdate())
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("heck constraint");
  }

  @Test
  void usageRowPersistsWithoutBeneficiaryReference() {
    UUID operation = UUID.randomUUID();
    UUID beneficiary = UUID.randomUUID();
    usage.save(new TransferBeneficiaryUsage(operation, beneficiary, "owner-1", "Ahorros"));
    usage.flush();
    entities.clear();

    assertThat(usage.findById(operation)).isPresent();
    assertThat(usage.findById(operation).get().getBeneficiaryId()).isEqualTo(beneficiary);
    assertThat(usage.findById(operation).get().getBeneficiaryOwner()).isEqualTo("owner-1");
  }

  @Test
  void duplicateUsageOperationIdViolatesPk() {
    UUID operation = UUID.randomUUID();
    usage.save(new TransferBeneficiaryUsage(operation, UUID.randomUUID(), "owner-1", "Ahorros"));
    usage.flush();

    assertThatThrownBy(
            () ->
                entities
                    .getEntityManager()
                    .createNativeQuery(
                        "insert into transfer_beneficiary_usage (operation_id, beneficiary_id,"
                            + " beneficiary_owner, beneficiary_alias, created_at) values ('"
                            + operation
                            + "', '"
                            + UUID.randomUUID()
                            + "', 'owner-1', 'Otros', now())")
                    .executeUpdate())
        .isInstanceOf(PersistenceException.class);
  }

  @Test
  void usageJavaInvariantRejectsNulls() {
    assertThatThrownBy(
            () -> new TransferBeneficiaryUsage(null, UUID.randomUUID(), "owner-1", "Ahorros"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TransferBeneficiaryUsage(UUID.randomUUID(), null, "owner-1", "Ahorros"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TransferBeneficiaryUsage(UUID.randomUUID(), UUID.randomUUID(), "  ", "A"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
