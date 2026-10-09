package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.bancoelcapital.accounts.internal.Account;
import com.bancoelcapital.financialops.core.FinancialOperation;
import com.bancoelcapital.financialops.core.FinancialOperationStatus;
import com.bancoelcapital.financialops.core.FinancialOperationType;
import com.bancoelcapital.financialops.core.Movement;
import com.bancoelcapital.financialops.core.MovementDirection;
import com.bancoelcapital.financialops.deposit.DepositOperationIdempotency;
import com.bancoelcapital.financialops.deposit.DepositOperationOutcome;

import jakarta.persistence.PersistenceException;

/**
 * Transfer foundation persistence (ADR-18, Phase 1, Option A). Proves the structural model only:
 * TRANSFER operation with account_id identifying the SOURCE, one DEBIT movement on the source plus
 * one CREDIT movement on the destination sharing the same operation_id, and a dedicated idempotency
 * namespace separate from deposits. No funds, concurrency, API, authorization, or audit behavior
 * here.
 */
@DataJpaTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfers;MODE=PostgreSQL",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferFoundationPersistenceTest {

  @Autowired TestEntityManager entities;

  @Test
  void persistsTransferOperationWithSourceAccountAndLinkedDebitAndCreditMovements() {
    UUID sourceId = UUID.randomUUID();
    UUID destinationId = UUID.randomUUID();
    entities.persist(new Account(sourceId, "holder-source", "DOP", "BASIC"));
    entities.persist(new Account(destinationId, "holder-dest", "DOP", "BASIC"));

    UUID operationId = UUID.randomUUID();
    entities.persist(
        new FinancialOperation(
            operationId,
            FinancialOperationType.TRANSFER,
            "holder-source",
            sourceId,
            25_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED));
    UUID debitMovementId = UUID.randomUUID();
    UUID creditMovementId = UUID.randomUUID();
    entities.persist(
        new Movement(
            debitMovementId, operationId, sourceId, MovementDirection.DEBIT, 25_00L, "DOP"));
    entities.persist(
        new Movement(
            creditMovementId, operationId, destinationId, MovementDirection.CREDIT, 25_00L, "DOP"));
    entities.persist(
        new TransferOperationIdempotency(
            "t1", operationId, "hash", TransferOperationOutcome.CONFIRMED));
    entities.flush();
    entities.clear();

    FinancialOperation stored = entities.find(FinancialOperation.class, operationId);
    assertThat(stored.getOperationType()).isEqualTo(FinancialOperationType.TRANSFER);
    // Option A: the operation row identifies the source account.
    assertThat(stored.getAccountId()).isEqualTo(sourceId);

    Movement debit = entities.find(Movement.class, debitMovementId);
    Movement credit = entities.find(Movement.class, creditMovementId);
    assertThat(debit.getOperationId()).isEqualTo(operationId);
    assertThat(credit.getOperationId()).isEqualTo(operationId);
    // Origin/destination cannot be confused: DEBIT is the source, CREDIT is the destination.
    assertThat(debit.getDirection()).isEqualTo(MovementDirection.DEBIT);
    assertThat(debit.getAccountId()).isEqualTo(sourceId);
    assertThat(credit.getDirection()).isEqualTo(MovementDirection.CREDIT);
    assertThat(credit.getAccountId()).isEqualTo(destinationId);

    assertThat(entities.find(TransferOperationIdempotency.class, "t1")).isNotNull();
  }

  @Test
  void transferIdempotencyNamespaceIsSeparateFromDeposits() {
    UUID sourceId = UUID.randomUUID();
    entities.persist(new Account(sourceId, "holder-source", "DOP", "BASIC"));

    UUID depositOperationId = UUID.randomUUID();
    entities.persist(
        new FinancialOperation(
            depositOperationId,
            FinancialOperationType.DEPOSIT,
            "holder-source",
            sourceId,
            10_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED));
    UUID transferOperationId = UUID.randomUUID();
    entities.persist(
        new FinancialOperation(
            transferOperationId,
            FinancialOperationType.TRANSFER,
            "holder-source",
            sourceId,
            4_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED));
    entities.persist(
        new DepositOperationIdempotency(
            "shared-key", depositOperationId, "hash", DepositOperationOutcome.CONFIRMED));
    entities.persist(
        new TransferOperationIdempotency(
            "shared-key", transferOperationId, "hash", TransferOperationOutcome.CONFIRMED));
    entities.flush();
    entities.clear();

    assertThat(entities.find(DepositOperationIdempotency.class, "shared-key")).isNotNull();
    assertThat(entities.find(TransferOperationIdempotency.class, "shared-key")).isNotNull();
  }

  @Test
  void duplicateTransferIdempotencyKeyViolatesConstraint() {
    entities.persist(
        new TransferOperationIdempotency(
            "t-dup", UUID.randomUUID(), "h", TransferOperationOutcome.CONFIRMED));
    entities.flush();
    entities.clear();

    entities.persist(
        new TransferOperationIdempotency(
            "t-dup", UUID.randomUUID(), "h", TransferOperationOutcome.CONFIRMED));
    assertThatThrownBy(entities::flush).isInstanceOf(PersistenceException.class);
  }
}
