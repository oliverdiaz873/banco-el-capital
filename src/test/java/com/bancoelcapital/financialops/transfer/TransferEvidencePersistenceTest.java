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
 * Transfer intent evidence persistence (ADR-21, V11-V13). Proves the V11-equivalent shape on H2:
 * historical all-NULL rows persist, complete coherent rows persist per path, direct rows persist
 * their destination, partial and incoherent rows are rejected by real CHECK constraints on flush, a
 * CONFIRMED row cannot omit its operation reference, and the usage table carries no physical FK.
 * Java invariants are proven without persistence; database invariants are proven only by
 * persist-plus-flush, never by construction alone.
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
    assertThat(found.getDestinationAccountId()).isNull();
    assertThat(found.getPath()).isNull();
    assertThat(found.getAmountMinorUnits()).isNull();
    assertThat(found.getCurrency()).isNull();
  }

  @Test
  void newCompleteDirectRowPersists() {
    UUID source = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    TransferOperationIdempotency stored =
        new TransferOperationIdempotency(
            "k-direct",
            UUID.randomUUID(),
            "hash",
            TransferOperationOutcome.CONFIRMED,
            source,
            null,
            destination,
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
    assertThat(found.getDestinationAccountId()).isEqualTo(destination);
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
            null,
            "beneficiary-transfer",
            100L,
            "DOP");
    entities.persist(stored);
    entities.flush();
    entities.clear();

    TransferOperationIdempotency found =
        entities.find(TransferOperationIdempotency.class, "k-benef");
    assertThat(found.getBeneficiaryId()).isEqualTo(beneficiary);
    assertThat(found.getDestinationAccountId()).isNull();
    assertThat(found.getPath()).isEqualTo("beneficiary-transfer");
  }

  @Test
  void javaInvariantRejectsIncoherentIntent() {
    UUID source = UUID.randomUUID();
    UUID beneficiary = UUID.randomUUID();
    UUID destination = UUID.randomUUID();

    assertInvariantRejected(source, null, null, null, 100L, "DOP", false);
    assertInvariantRejected(source, null, null, "direct", 100L, "DOP", false);
    assertInvariantRejected(source, beneficiary, destination, "direct", 100L, "DOP", false);
    assertInvariantRejected(source, null, null, "beneficiary-transfer", 100L, "DOP", false);
    assertInvariantRejected(
        source, beneficiary, destination, "beneficiary-transfer", 100L, "DOP", false);
    assertInvariantRejected(source, null, destination, "other", 100L, "DOP", false);
    assertInvariantRejected(null, null, destination, "direct", 100L, "DOP", false);
    assertInvariantRejected(source, null, destination, "direct", 100L, "DOP", true);
  }

  private static void assertInvariantRejected(
      UUID source,
      UUID beneficiary,
      UUID destination,
      String path,
      Long amount,
      String currency,
      boolean confirmedWithoutOperation) {
    assertThatThrownBy(
            () ->
                new TransferOperationIdempotency(
                    "k",
                    confirmedWithoutOperation ? null : UUID.randomUUID(),
                    "hash",
                    confirmedWithoutOperation
                        ? TransferOperationOutcome.CONFIRMED
                        : TransferOperationOutcome.REJECTED,
                    source,
                    beneficiary,
                    destination,
                    path,
                    amount,
                    currency))
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

    assertThat(clauses).hasSizeGreaterThanOrEqualTo(4);
    String combined = String.join(" | ", clauses).toLowerCase();
    assertThat(combined).contains("beneficiary_id");
    assertThat(combined).contains("destination_account_id");
    assertThat(combined).contains("path");
    assertThat(combined).contains("amount_minor_units");
    assertThat(combined).contains("operation_id");
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
                            + " destination_account_id, path, amount_minor_units, currency)"
                            + " values ('k-direct-b', NULL, 'h', 'REJECTED', now(), '"
                            + UUID.randomUUID()
                            + "', '"
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
  void cleanSchemaInventoryHasNoConfirmedWithoutOperation() {
    Number invalid =
        (Number)
            entities
                .getEntityManager()
                .createNativeQuery(
                    "select count(*) from transfer_operation_idempotency"
                        + " where outcome = 'CONFIRMED' and operation_id is null")
                .getSingleResult();
    assertThat(invalid.longValue()).isZero();
  }

  @Test
  void dbCheckRejectsConfirmedWithoutOperationId() {
    assertThatThrownBy(
            () ->
                entities
                    .getEntityManager()
                    .createNativeQuery(
                        "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                            + " request_hash, outcome, created_at, source_account_id, beneficiary_id,"
                            + " destination_account_id, path, amount_minor_units, currency)"
                            + " values ('k-confirmed-no-op', NULL, 'h', 'CONFIRMED', now(), '"
                            + UUID.randomUUID()
                            + "', NULL, '"
                            + UUID.randomUUID()
                            + "', 'direct', 100, 'DOP')")
                    .executeUpdate())
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("heck constraint");
  }

  @Test
  void dbCheckRejectsDirectWithoutDestination() {
    assertThatThrownBy(
            () ->
                entities
                    .getEntityManager()
                    .createNativeQuery(
                        "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                            + " request_hash, outcome, created_at, source_account_id, beneficiary_id,"
                            + " destination_account_id, path, amount_minor_units, currency)"
                            + " values ('k-direct-no-dest', NULL, 'h', 'REJECTED', now(), '"
                            + UUID.randomUUID()
                            + "', NULL, NULL, 'direct', 100, 'DOP')")
                    .executeUpdate())
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("heck constraint");
  }

  @Test
  void dbCheckRejectsBeneficiaryWithDestination() {
    assertThatThrownBy(
            () ->
                entities
                    .getEntityManager()
                    .createNativeQuery(
                        "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                            + " request_hash, outcome, created_at, source_account_id, beneficiary_id,"
                            + " destination_account_id, path, amount_minor_units, currency)"
                            + " values ('k-benef-dest', NULL, 'h', 'REJECTED', now(), '"
                            + UUID.randomUUID()
                            + "', '"
                            + UUID.randomUUID()
                            + "', '"
                            + UUID.randomUUID()
                            + "', 'beneficiary-transfer', 100, 'DOP')")
                    .executeUpdate())
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("heck constraint");
  }

  @Test
  void dbCheckAcceptsDirectWithDestination() {
    entities
        .getEntityManager()
        .createNativeQuery(
            "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                + " request_hash, outcome, created_at, source_account_id, beneficiary_id,"
                + " destination_account_id, path, amount_minor_units, currency)"
                + " values ('k-direct-ok', NULL, 'h', 'REJECTED', now(), '"
                + UUID.randomUUID()
                + "', NULL, '"
                + UUID.randomUUID()
                + "', 'direct', 100, 'DOP')")
        .executeUpdate();

    Number rows =
        (Number)
            entities
                .getEntityManager()
                .createNativeQuery(
                    "select count(*) from transfer_operation_idempotency"
                        + " where idempotency_key = 'k-direct-ok' and destination_account_id is not null")
                .getSingleResult();
    assertThat(rows.longValue()).isOne();
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
