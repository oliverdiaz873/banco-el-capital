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
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * P0.2: rejection identity persistence through the real TransferService on H2. A direct rejection
 * persists direct intent with a null beneficiary; a beneficiary-mode rejection persists the
 * beneficiary path with the supplied beneficiary id and the beneficiary-specific request hash.
 * Neither creates movements. Retrying the same beneficiary key replays the stored rejection.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-reject-identity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@Transactional
class TransferRejectionIdentityPersistenceTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @PersistenceContext EntityManager entities;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;

  @BeforeEach
  void setup() {
    String sourceHolder = "holder-tri-src";
    String destinationHolder = "holder-tri-dst";
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    var destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-tri", sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "d-tri", destinationActor);
    sourceId =
        accounts
            .create(new AccountCreationCommand(sourceHolder, "DOP", "BASIC"), "a-tri", sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "b-tri",
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tri", sourceActor);
  }

  @Test
  void directRejectionPersistsDirectPathWithNullBeneficiary() {
    TransferCommand command = new TransferCommand(sourceId, UUID.randomUUID(), 40_00L, "DOP");

    assertThatThrownBy(() -> transfers.transfer(command, "tri-direct", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    Map<String, Object> row = intentRow("tri-direct");
    assertThat(row.get("outcome")).isEqualTo("REJECTED");
    assertThat(row.get("source_account_id")).isEqualTo(sourceId);
    assertThat(row.get("beneficiary_id")).isNull();
    assertThat(row.get("path")).isEqualTo("direct");
    assertThat(row.get("amount_minor_units")).isEqualTo(40_00L);
    assertThat(row.get("currency")).isEqualTo("DOP");
    assertThat(row.get("request_hash")).isEqualTo(TransferService.requestHash(command));
    assertThat(operationMovements(row.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(rejectionAudits("tri-direct")).isEqualTo(1);
  }

  @Test
  void beneficiaryRejectionPersistsBeneficiaryPathWithSuppliedId() {
    UUID beneficiaryId = UUID.randomUUID();
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");

    assertThatThrownBy(() -> transfers.transfer(command, "tri-beneficiary", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    Map<String, Object> row = intentRow("tri-beneficiary");
    assertThat(row.get("outcome")).isEqualTo("REJECTED");
    assertThat(row.get("source_account_id")).isEqualTo(sourceId);
    assertThat(row.get("beneficiary_id")).isEqualTo(beneficiaryId);
    assertThat(row.get("path")).isEqualTo("beneficiary-transfer");
    assertThat(row.get("amount_minor_units")).isEqualTo(40_00L);
    assertThat(row.get("currency")).isEqualTo("DOP");
    assertThat(row.get("request_hash")).isEqualTo(TransferService.requestHash(command));
    assertThat(operationMovements(row.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(rejectionAudits("tri-beneficiary")).isEqualTo(1);
  }

  @Test
  void sameKeyDifferentBeneficiaryConflictsWithoutNewEffects() {
    // P1a.4: the second beneficiary never exists, so a 409 (instead of a 422 invalid-beneficiary
    // rejection) proves the conflict is detected before any live beneficiary resolution. The
    // original rejected operation stays identical with a single TRANSFER_CONFLICT audit.
    UUID first = UUID.randomUUID();
    TransferCommand firstCommand = new TransferCommand(sourceId, null, first, 40_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(firstCommand, "tri-conflict", sourceActor))
        .isInstanceOf(TransferOperationException.class);
    Map<String, Object> before = intentRow("tri-conflict");

    UUID second = UUID.randomUUID();
    TransferCommand secondCommand = new TransferCommand(sourceId, null, second, 40_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(secondCommand, "tri-conflict", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);

    assertThat(intentRow("tri-conflict")).isEqualTo(before);
    assertThat(before.get("beneficiary_id")).isEqualTo(first);
    assertThat(before.get("path")).isEqualTo("beneficiary-transfer");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT'"
                    + " and resource_id = ?",
                Integer.class,
                "tri-conflict"))
        .isEqualTo(1);
    assertThat(operationMovements(before.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
  }

  @Test
  void sameKeyDifferentAmountConflictsAndPreservesRow() {
    UUID beneficiaryId = UUID.randomUUID();
    TransferCommand first = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(first, "tri-amount", sourceActor))
        .isInstanceOf(TransferOperationException.class);
    Map<String, Object> before = intentRow("tri-amount");

    TransferCommand second = new TransferCommand(sourceId, null, beneficiaryId, 50_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(second, "tri-amount", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);

    assertThat(intentRow("tri-amount")).isEqualTo(before);
    assertThat(operationMovements(before.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
  }

  @Test
  void directSameKeyDifferentDestinationConflictsAndPreservesRow() {
    TransferCommand first = new TransferCommand(sourceId, UUID.randomUUID(), 40_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(first, "tri-dest", sourceActor))
        .isInstanceOf(TransferOperationException.class);
    Map<String, Object> before = intentRow("tri-dest");

    TransferCommand second = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(second, "tri-dest", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);

    assertThat(intentRow("tri-dest")).isEqualTo(before);
    assertThat(before.get("path")).isEqualTo("direct");
    assertThat(operationMovements(before.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
  }

  @Test
  void beneficiaryRejectionRetryReplaysStoredRejection() {
    // P1a ordering: the existing-key check runs before beneficiary resolution, so a retry with
    // the same key and payload replays the stored rejection without consulting the live
    // beneficiary table and without writing anything new. Direct unknown-destination retries keep
    // their pre-existing fresh-rejection behavior (see directRejectionRetry... below).
    UUID beneficiaryId = UUID.randomUUID();
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(command, "tri-replay", sourceActor))
        .isInstanceOf(TransferOperationException.class);
    Map<String, Object> before = intentRow("tri-replay");

    assertThatThrownBy(() -> transfers.transfer(command, "tri-replay", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    Map<String, Object> after = intentRow("tri-replay");
    assertThat(after).isEqualTo(before);
    assertThat(after.get("path")).isEqualTo("beneficiary-transfer");
    assertThat(after.get("beneficiary_id")).isEqualTo(beneficiaryId);
    assertThat(after.get("request_hash")).isEqualTo(TransferService.requestHash(command));
    assertThat(operationMovements(after.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(rejectionAudits("tri-replay")).isEqualTo(1);
  }

  @Test
  void directRejectionRetryCreatesFreshRejectionWithoutNewEffects() {
    // Pre-existing direct semantics, preserved exactly by P0.2 and P1a: the destination lookup
    // runs before the existing-key fast path, so every retry persists a fresh rejection that
    // merge-updates the row (new operation, +1 audit), with zero movements. Beneficiary retries
    // instead replay (existing-key check precedes resolution); see the test above.
    TransferCommand command = new TransferCommand(sourceId, UUID.randomUUID(), 40_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(command, "tri-direct-retry", sourceActor))
        .isInstanceOf(TransferOperationException.class);
    Map<String, Object> before = intentRow("tri-direct-retry");

    assertThatThrownBy(() -> transfers.transfer(command, "tri-direct-retry", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    Map<String, Object> after = intentRow("tri-direct-retry");
    assertThat(after.get("outcome")).isEqualTo("REJECTED");
    assertThat(after.get("beneficiary_id")).isNull();
    assertThat(after.get("path")).isEqualTo("direct");
    assertThat(after.get("request_hash")).isEqualTo(TransferService.requestHash(command));
    assertThat(after.get("operation_id")).isNotEqualTo(before.get("operation_id"));
    assertThat(operationMovements(before.get("operation_id"))).isZero();
    assertThat(operationMovements(after.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(rejectionAudits("tri-direct-retry")).isEqualTo(2);
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
    Integer count =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id ="
                + " m.operation_id where o.id = ?",
            Integer.class,
            operationId);
    return count == null ? 0 : count;
  }

  private int rejectionAudits(String key) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from audit_events where action = 'TRANSFER_REJECTED'"
                + " and resource_id = ?",
            Integer.class,
            key);
    return count == null ? 0 : count;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
