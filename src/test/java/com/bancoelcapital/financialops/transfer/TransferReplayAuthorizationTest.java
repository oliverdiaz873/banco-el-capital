package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryCommand;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.core.FinancialOperation;
import com.bancoelcapital.financialops.core.FinancialOperationStatus;
import com.bancoelcapital.financialops.core.FinancialOperationType;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Scope A1: POST existing-key authorization against persisted snapshots. Denial is
 * indistinguishable (401/403) with zero writes and reveals nothing about match versus conflict.
 * Owner snapshots come from persisted usage evidence, never from the live beneficiary table. A
 * confirmed beneficiary row without an owner snapshot fails closed.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-replay-auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@Transactional
class TransferReplayAuthorizationTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired BeneficiaryService beneficiaries;

  @Autowired JdbcTemplate jdbc;

  @PersistenceContext EntityManager entities;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor otherActor;

  @BeforeEach
  void setup() {
    sourceActor = new AuthenticatedActor("holder-tra-src", Set.of());
    var destinationActor = new AuthenticatedActor("holder-tra-dst", Set.of());
    otherActor = new AuthenticatedActor("holder-tra-other", Set.of());
    customers.create(new CustomerCreationCommand("holder-tra-src", null), "c-tra", sourceActor);
    customers.create(
        new CustomerCreationCommand("holder-tra-dst", null), "d-tra", destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand("holder-tra-src", "DOP", "BASIC"), "a-tra", sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand("holder-tra-dst", "DOP", "BASIC"),
                "b-tra",
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tra", sourceActor);
  }

  @Test
  void directReplayAuthorizedWritesNothing() {
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    TransferResult first = transfers.transfer(command, "tra-direct", sourceActor);
    Map<String, Object> before = intentRow("tra-direct");

    TransferResult replayed = transfers.transfer(command, "tra-direct", sourceActor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(first.operationId());
    assertThat(intentRow("tra-direct")).isEqualTo(before);
    assertThat(operationMovements(first.operationId())).isEqualTo(2);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void directReplayDeniedWritesNothing() {
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    transfers.transfer(command, "tra-direct-deny", sourceActor);
    Map<String, Object> before = intentRow("tra-direct-deny");

    assertThatThrownBy(() -> transfers.transfer(command, "tra-direct-deny", otherActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    assertThat(intentRow("tra-direct-deny")).isEqualTo(before);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
    assertThat(count("select count(*) from audit_events where resource_id = ?", "tra-direct-deny"))
        .isZero();
  }

  @Test
  void beneficiaryReplayAuthorizedWritesNothing() {
    UUID beneficiaryId = saveBeneficiary("tra-ben");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    TransferResult first = transfers.transfer(command, "tra-ben-use", sourceActor);

    TransferResult replayed = transfers.transfer(command, "tra-ben-use", sourceActor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(first.operationId());
    assertThat(usageCount(first.operationId())).isEqualTo(1);
    assertThat(operationMovements(first.operationId())).isEqualTo(2);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                    + " and resource_id = ?",
                first.operationId().toString()))
        .isEqualTo(1);
  }

  @Test
  void beneficiaryReplayOwnerDeniedWritesNothing() {
    UUID beneficiaryId = saveBeneficiary("tra-owner");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    TransferResult first = transfers.transfer(command, "tra-owner-use", sourceActor);
    jdbc.update(
        "update transfer_beneficiary_usage set beneficiary_owner = ? where operation_id = ?",
        "holder-tra-other",
        first.operationId());
    entities.clear();

    assertThatThrownBy(() -> transfers.transfer(command, "tra-owner-use", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    assertThat(usageCount(first.operationId())).isEqualTo(1);
    assertThat(operationMovements(first.operationId())).isEqualTo(2);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT'"
                    + " and resource_id = ?",
                "tra-owner-use"))
        .isZero();
  }

  @Test
  void beneficiaryReplayForeignActorDeniedEvenWhenConflicting() {
    UUID beneficiaryId = saveBeneficiary("tra-foreign");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    transfers.transfer(command, "tra-foreign-use", sourceActor);
    TransferCommand conflicting = new TransferCommand(sourceId, null, beneficiaryId, 50_00L, "DOP");

    assertThatThrownBy(() -> transfers.transfer(conflicting, "tra-foreign-use", otherActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT'"
                    + " and resource_id = ?",
                "tra-foreign-use"))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void confirmedWithoutOwnerSnapshotFailsClosed() {
    UUID beneficiaryId = UUID.randomUUID();
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    entities.persist(
        new TransferOperationIdempotency(
            "tra-fc",
            null,
            TransferService.requestHash(command),
            TransferOperationOutcome.CONFIRMED,
            sourceId,
            beneficiaryId,
            "beneficiary-transfer",
            40_00L,
            "DOP"));
    entities.flush();

    assertThatThrownBy(() -> transfers.transfer(command, "tra-fc", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    assertThat(count("select count(*) from financial_operations where operation_type = 'TRANSFER'"))
        .isZero();
    assertThat(count("select count(*) from transfer_beneficiary_usage")).isZero();
    assertThat(count("select count(*) from audit_events where resource_id = ?", "tra-fc")).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  @Test
  void deletedBeneficiaryReplayUsesPersistedSnapshots() {
    UUID beneficiaryId = saveBeneficiary("tra-del");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    TransferResult first = transfers.transfer(command, "tra-del-use", sourceActor);
    assertThat(beneficiaries.remove(beneficiaryId, sourceActor)).isTrue();

    TransferResult replayed = transfers.transfer(command, "tra-del-use", sourceActor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(first.operationId());
    assertThat(usageCount(first.operationId())).isEqualTo(1);
    assertThat(operationMovements(first.operationId())).isEqualTo(2);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void confirmedMismatchConflictsOnlyAfterAuthorization() {
    UUID beneficiaryId = saveBeneficiary("tra-mismatch");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    TransferResult first = transfers.transfer(command, "tra-mismatch-use", sourceActor);
    TransferCommand other = new TransferCommand(sourceId, null, beneficiaryId, 50_00L, "DOP");

    assertThatThrownBy(() -> transfers.transfer(other, "tra-mismatch-use", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);

    assertThat(usageCount(first.operationId())).isEqualTo(1);
    assertThat(operationMovements(first.operationId())).isEqualTo(2);
    entities.flush();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT'"
                    + " and resource_id = ?",
                "tra-mismatch-use"))
        .isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void historicalRowReplaysWithLegacyCompatibility() {
    UUID operationId = UUID.randomUUID();
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    entities.persist(
        new FinancialOperation(
            operationId,
            FinancialOperationType.TRANSFER,
            "holder-tra-src",
            sourceId,
            40_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED));
    entities.persist(
        new TransferOperationIdempotency(
            "tra-hist",
            operationId,
            TransferService.requestHash(command),
            TransferOperationOutcome.CONFIRMED,
            null,
            null,
            null,
            null,
            null));
    entities.flush();

    TransferResult replayed = transfers.transfer(command, "tra-hist", sourceActor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(operationId);
  }

  private UUID saveBeneficiary(String key) {
    return beneficiaries
        .create(new BeneficiaryCommand(destinationId, "Ahorros"), key, sourceActor)
        .beneficiaryId();
  }

  private Map<String, Object> intentRow(String key) {
    entities.flush();
    return jdbc.queryForMap(
        "select operation_id, outcome, request_hash, source_account_id, beneficiary_id, path,"
            + " amount_minor_units, currency from transfer_operation_idempotency"
            + " where idempotency_key = ?",
        key);
  }

  private int operationMovements(Object operationId) {
    Integer total =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id ="
                + " m.operation_id where o.id = ?",
            Integer.class,
            operationId);
    return total == null ? 0 : total;
  }

  private int usageCount(Object operationId) {
    Integer total =
        jdbc.queryForObject(
            "select count(*) from transfer_beneficiary_usage where operation_id = ?",
            Integer.class,
            operationId);
    return total == null ? 0 : total;
  }

  private int count(String sql, Object... args) {
    Integer total = jdbc.queryForObject(sql, Integer.class, args);
    return total == null ? 0 : total;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
