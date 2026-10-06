package com.bancoelcapital.financialops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.bancoelcapital.accounts.Account;

import jakarta.persistence.PersistenceException;

/**
 * Withdrawal foundation persistence (ADR-16, Phase 1). Proves the structural model only: WITHDRAWAL
 * operation, DEBIT movement, and a dedicated idempotency namespace separate from deposits. No
 * funds, concurrency, API, authorization, or audit behavior here.
 */
@DataJpaTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:withdrawals;MODE=PostgreSQL",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class WithdrawalFoundationPersistenceTest {

  @Autowired TestEntityManager entities;

  @Test
  void persistsWithdrawalOperationDebitMovementAndIdempotency() {
    UUID accountId = UUID.randomUUID();
    entities.persist(new Account(accountId, "holder-1", "DOP", "BASIC"));

    UUID operationId = UUID.randomUUID();
    entities.persist(
        new FinancialOperation(
            operationId,
            FinancialOperationType.WITHDRAWAL,
            "holder-1",
            accountId,
            40_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED));
    entities.persist(
        new Movement(
            UUID.randomUUID(), operationId, accountId, MovementDirection.DEBIT, 40_00L, "DOP"));
    entities.persist(
        new WithdrawalOperationIdempotency(
            "w1", operationId, "hash", WithdrawalOperationOutcome.CONFIRMED));
    entities.flush();
    entities.clear();

    assertThat(entities.find(FinancialOperation.class, operationId).getOperationType())
        .isEqualTo(FinancialOperationType.WITHDRAWAL);
    assertThat(entities.find(WithdrawalOperationIdempotency.class, "w1")).isNotNull();
  }

  @Test
  void withdrawalIdempotencyNamespaceIsSeparateFromDeposits() {
    UUID accountId = UUID.randomUUID();
    entities.persist(new Account(accountId, "holder-1", "DOP", "BASIC"));

    UUID depositOperationId = UUID.randomUUID();
    entities.persist(
        new FinancialOperation(
            depositOperationId,
            FinancialOperationType.DEPOSIT,
            "holder-1",
            accountId,
            10_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED));
    UUID withdrawalOperationId = UUID.randomUUID();
    entities.persist(
        new FinancialOperation(
            withdrawalOperationId,
            FinancialOperationType.WITHDRAWAL,
            "holder-1",
            accountId,
            4_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED));
    entities.persist(
        new DepositOperationIdempotency(
            "shared-key", depositOperationId, "hash", DepositOperationOutcome.CONFIRMED));
    entities.persist(
        new WithdrawalOperationIdempotency(
            "shared-key", withdrawalOperationId, "hash", WithdrawalOperationOutcome.CONFIRMED));
    entities.flush();
    entities.clear();

    assertThat(entities.find(DepositOperationIdempotency.class, "shared-key")).isNotNull();
    assertThat(entities.find(WithdrawalOperationIdempotency.class, "shared-key")).isNotNull();
  }

  @Test
  void duplicateWithdrawalIdempotencyKeyViolatesConstraint() {
    entities.persist(
        new WithdrawalOperationIdempotency(
            "w-dup", UUID.randomUUID(), "h", WithdrawalOperationOutcome.CONFIRMED));
    entities.flush();
    entities.clear();

    entities.persist(
        new WithdrawalOperationIdempotency(
            "w-dup", UUID.randomUUID(), "h", WithdrawalOperationOutcome.CONFIRMED));
    assertThatThrownBy(entities::flush).isInstanceOf(PersistenceException.class);
  }
}
