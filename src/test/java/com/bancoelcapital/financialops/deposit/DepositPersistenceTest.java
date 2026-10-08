package com.bancoelcapital.financialops.deposit;

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

import jakarta.persistence.PersistenceException;

/** Repository slice on H2 (transitory until Docker enables Testcontainers PG). */
@DataJpaTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:deposits;MODE=PostgreSQL",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class DepositPersistenceTest {

  @Autowired TestEntityManager entities;

  @Test
  void persistsOperationMovementIdempotencyAndZeroBalance() {
    UUID accountId = UUID.randomUUID();
    entities.persist(new Account(accountId, "holder-1", "DOP", "BASIC"));

    UUID operationId = UUID.randomUUID();
    entities.persist(
        new FinancialOperation(
            operationId,
            FinancialOperationType.DEPOSIT,
            "holder-1",
            accountId,
            10_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED));
    entities.persist(
        new Movement(
            UUID.randomUUID(), operationId, accountId, MovementDirection.CREDIT, 10_00L, "DOP"));
    entities.persist(
        new DepositOperationIdempotency(
            "k1", operationId, "hash", DepositOperationOutcome.CONFIRMED));
    entities.flush();
    entities.clear();

    assertThat(entities.find(Account.class, accountId).getBalanceMinorUnits()).isZero();
    assertThat(entities.find(FinancialOperation.class, operationId)).isNotNull();
    assertThat(entities.find(DepositOperationIdempotency.class, "k1")).isNotNull();
    entities.flush();
    entities.clear();

    entities.persist(
        new DepositOperationIdempotency(
            "k1", UUID.randomUUID(), "h", DepositOperationOutcome.CONFIRMED));
    assertThatThrownBy(entities::flush).isInstanceOf(PersistenceException.class);
  }
}
