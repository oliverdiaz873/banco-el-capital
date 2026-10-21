package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.core.FinancialOperation;
import com.bancoelcapital.financialops.core.FinancialOperationStatus;
import com.bancoelcapital.financialops.core.FinancialOperationType;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.financialops.transfer.TransferBeneficiaryUsage;
import com.bancoelcapital.financialops.transfer.TransferCommand;
import com.bancoelcapital.financialops.transfer.TransferOperationException;
import com.bancoelcapital.financialops.transfer.TransferOperationIdempotency;
import com.bancoelcapital.financialops.transfer.TransferOperationOutcome;
import com.bancoelcapital.financialops.transfer.TransferResult;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * Transfer beneficiary evidence on real PostgreSQL (ADR-21, Phase 2). Proves Flyway applies V11
 * literally, complete rows persist per path, partial/incoherent rows violate the production CHECK
 * constraints, and usage rows survive without any beneficiary row (no physical FK). Skipped without
 * Docker; a skip proves nothing about PostgreSQL compatibility.
 */
class TransferEvidencePostgresTest extends AbstractPostgresTest {

  @Autowired Flyway flyway;

  @PersistenceContext EntityManager entities;

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @Autowired PlatformTransactionManager transactionManager;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  String tag;

  @BeforeEach
  void setupAccounts() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String sourceHolder = "holder-tev-src-" + tag;
    String destinationHolder = "holder-tev-dst-" + tag;
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    var destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-tev-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "d-tev-" + tag, destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceHolder, "DOP", "BASIC"),
                "a-tev-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "b-tev-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tev-" + tag, sourceActor);
  }

  @Test
  @Transactional
  void v11AppliedViaFlyway() {
    assertThat(
            Arrays.stream(flyway.info().applied())
                .anyMatch(
                    applied ->
                        applied.getVersion() != null
                            && applied.getVersion().getVersion().equals("11")))
        .isTrue();
  }

  @Test
  @Transactional
  void completeRowsPersistOnPostgres() {
    UUID operation = UUID.randomUUID();
    UUID beneficiary = UUID.randomUUID();
    // transfer_operation_idempotency.operation_id REFERENCES financial_operations(id) in the
    // Flyway-managed schema (V8): persist the referenced operation first, mirroring the
    // production write order. account_id stays a logical reference (no FK to accounts).
    entities.persist(
        new FinancialOperation(
            operation,
            FinancialOperationType.TRANSFER,
            "postgres-test",
            UUID.randomUUID(),
            100L,
            "DOP",
            FinancialOperationStatus.CONFIRMED));
    entities.persist(
        new TransferOperationIdempotency(
            "k-pg-benef",
            operation,
            "hash",
            TransferOperationOutcome.CONFIRMED,
            UUID.randomUUID(),
            beneficiary,
            "beneficiary-transfer",
            100L,
            "DOP"));
    entities.persist(new TransferBeneficiaryUsage(operation, beneficiary, "owner-1", "Ahorros"));
    entities.flush();
    entities.clear();

    TransferOperationIdempotency found =
        entities.find(TransferOperationIdempotency.class, "k-pg-benef");
    assertThat(found.getBeneficiaryId()).isEqualTo(beneficiary);
    assertThat(found.getPath()).isEqualTo("beneficiary-transfer");
  }

  /**
   * H-02 boundary proof on the real schema: PostgreSQL's V8 foreign key rejects a CONFIRMED
   * idempotency row whose non-null operation_id has no financial_operations row. The failed insert
   * runs in its own transaction so the aborted transaction cannot contaminate later checks. This is
   * not a substitute for the H2 application-level fail-closed test.
   */
  @Test
  void orphanOperationReferenceIsRejectedByForeignKey() {
    String key = "k-pg-orphan-" + tag;
    UUID orphanOperationId = UUID.randomUUID();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> {
                      entities.persist(
                          new TransferOperationIdempotency(
                              key,
                              orphanOperationId,
                              "hash",
                              TransferOperationOutcome.CONFIRMED,
                              sourceId,
                              null,
                              "direct",
                              100L,
                              "DOP"));
                      entities.flush();
                      return null;
                    }))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("foreign key");

    Integer rows =
        jdbc.queryForObject(
            "select count(*) from transfer_operation_idempotency where idempotency_key = ?",
            Integer.class,
            key);
    assertThat(rows).isZero();
  }

  @Test
  @Transactional
  void checkConstraintsEnforcedOnPostgres() {
    assertThatThrownBy(
            () ->
                entities
                    .createNativeQuery(
                        "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                            + " request_hash, outcome, created_at, source_account_id, beneficiary_id,"
                            + " path, amount_minor_units, currency) values ('k-pg-partial', NULL, 'h',"
                            + " 'REJECTED', now(), '"
                            + UUID.randomUUID()
                            + "', NULL, NULL, 100, 'DOP')")
                    .executeUpdate())
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("heck constraint");
  }

  @Test
  @Transactional
  void usageSurvivesWithoutBeneficiaryRow() {
    UUID operation = UUID.randomUUID();
    entities.persist(new TransferBeneficiaryUsage(operation, UUID.randomUUID(), "owner-1", null));
    entities.flush();
    entities.clear();

    assertThat(entities.find(TransferBeneficiaryUsage.class, operation).getBeneficiaryOwner())
        .isEqualTo("owner-1");
  }

  @Test
  @Transactional
  void writerPersistsCompleteDirectIntentOnConfirm() {
    String key = "tev-confirm-" + tag;
    TransferResult result =
        transfers.transfer(
            new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), key, sourceActor);

    TransferOperationIdempotency stored = entities.find(TransferOperationIdempotency.class, key);
    assertThat(stored.getOutcome()).isEqualTo(TransferOperationOutcome.CONFIRMED);
    assertThat(stored.getOperationId()).isEqualTo(result.operationId());
    assertThat(stored.getSourceAccountId()).isEqualTo(sourceId);
    assertThat(stored.getBeneficiaryId()).isNull();
    assertThat(stored.getPath()).isEqualTo("direct");
    assertThat(stored.getAmountMinorUnits()).isEqualTo(40_00L);
    assertThat(stored.getCurrency()).isEqualTo("DOP");
    assertThat(movementCount(result.operationId(), "DEBIT")).isEqualTo(1);
    assertThat(movementCount(result.operationId(), "CREDIT")).isEqualTo(1);
  }

  @Test
  @Transactional
  void writerPersistsDirectIntentOnReject() {
    String key = "tev-reject-" + tag;
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, unknown, 40_00L, "DOP"), key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    TransferOperationIdempotency stored = entities.find(TransferOperationIdempotency.class, key);
    assertThat(stored.getOutcome()).isEqualTo(TransferOperationOutcome.REJECTED);
    assertThat(stored.getSourceAccountId()).isEqualTo(sourceId);
    assertThat(stored.getBeneficiaryId()).isNull();
    assertThat(stored.getPath()).isEqualTo("direct");
    assertThat(stored.getAmountMinorUnits()).isEqualTo(40_00L);
    assertThat(stored.getCurrency()).isEqualTo("DOP");
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
  }

  private int movementCount(UUID operationId, String direction) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from movements where operation_id = ? and direction = ?",
            Integer.class,
            operationId,
            direction);
    return count == null ? 0 : count;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
