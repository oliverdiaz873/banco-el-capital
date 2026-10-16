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
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * P1a: beneficiary transfers through the real resolver and persistence path on H2. Confirmation
 * uses the server-side resolved destination with dual authorization; missing and foreign
 * beneficiaries reject generically with zero movements. P2: confirmation persists the usage
 * snapshots atomically and extends the confirmation audit.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-beneficiary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@Transactional
class TransferBeneficiaryPersistenceTest {

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
  AuthenticatedActor destinationActor;

  @BeforeEach
  void setup() {
    sourceActor = new AuthenticatedActor("holder-tbp-src", Set.of());
    destinationActor = new AuthenticatedActor("holder-tbp-dst", Set.of());
    customers.create(new CustomerCreationCommand("holder-tbp-src", null), "c-tbp", sourceActor);
    customers.create(
        new CustomerCreationCommand("holder-tbp-dst", null), "d-tbp", destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand("holder-tbp-src", "DOP", "BASIC"), "a-tbp", sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand("holder-tbp-dst", "DOP", "BASIC"),
                "b-tbp",
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tbp", sourceActor);
  }

  @Test
  void confirmUsesServerSideResolvedDestination() {
    UUID beneficiaryId =
        beneficiaries
            .create(new BeneficiaryCommand(destinationId, "Ahorros"), "tbp-save", sourceActor)
            .beneficiaryId();
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");

    TransferResult result = transfers.transfer(command, "tbp-confirm", sourceActor);

    assertThat(result.created()).isTrue();
    assertThat(debitCount(result.operationId())).isEqualTo(1);
    assertThat(creditCount(result.operationId())).isEqualTo(1);
    assertThat(creditedAccount(result.operationId())).isEqualTo(destinationId);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
    Map<String, Object> row = intentRow("tbp-confirm");
    assertThat(row.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(row.get("source_account_id")).isEqualTo(sourceId);
    assertThat(row.get("beneficiary_id")).isEqualTo(beneficiaryId);
    assertThat(row.get("path")).isEqualTo("beneficiary-transfer");
    assertThat(row.get("request_hash")).isEqualTo(TransferService.requestHash(command));
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                    + " and resource_id = ?",
                result.operationId().toString()))
        .isEqualTo(1);
  }

  @Test
  void missingBeneficiaryRejectsGenericallyWithoutMovements() {
    UUID beneficiaryId = UUID.randomUUID();
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");

    assertThatThrownBy(() -> transfers.transfer(command, "tbp-missing", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                        == TransferOperationException.Kind.REJECTED
                    && e.getMessage().equals("invalid beneficiary"));

    Map<String, Object> row = intentRow("tbp-missing");
    assertThat(row.get("outcome")).isEqualTo("REJECTED");
    assertThat(row.get("beneficiary_id")).isEqualTo(beneficiaryId);
    assertThat(row.get("path")).isEqualTo("beneficiary-transfer");
    assertThat(row.get("request_hash")).isEqualTo(TransferService.requestHash(command));
    assertThat(operationMovements(row.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
    assertThat(rejectionAuditDetails("tbp-missing")).isEqualTo("invalid beneficiary");
    assertThat(usageCount(row.get("operation_id"))).isZero();
  }

  @Test
  void foreignBeneficiaryRejectsLikeMissing() {
    UUID foreignId =
        beneficiaries
            .create(
                new BeneficiaryCommand(destinationId, "Ahorros"), "tbp-foreign", destinationActor)
            .beneficiaryId();
    TransferCommand command = new TransferCommand(sourceId, null, foreignId, 40_00L, "DOP");

    assertThatThrownBy(() -> transfers.transfer(command, "tbp-foreign-use", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                        == TransferOperationException.Kind.REJECTED
                    && e.getMessage().equals("invalid beneficiary"));

    Map<String, Object> row = intentRow("tbp-foreign-use");
    assertThat(row.get("outcome")).isEqualTo("REJECTED");
    assertThat(row.get("beneficiary_id")).isEqualTo(foreignId);
    assertThat(row.get("path")).isEqualTo("beneficiary-transfer");
    assertThat(operationMovements(row.get("operation_id"))).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(rejectionAuditDetails("tbp-foreign-use")).isEqualTo("invalid beneficiary");
    assertThat(usageCount(row.get("operation_id"))).isZero();
  }

  @Test
  void confirmedBeneficiaryTransferPersistsUsageSnapshotsAtomically() {
    UUID beneficiaryId =
        beneficiaries
            .create(new BeneficiaryCommand(destinationId, "Ahorros"), "tbp-usage", sourceActor)
            .beneficiaryId();
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");

    TransferResult result = transfers.transfer(command, "tbp-usage-use", sourceActor);

    assertThat(result.created()).isTrue();
    Map<String, Object> usage = usageRow(result.operationId());
    assertThat(usage.get("beneficiary_id")).isEqualTo(beneficiaryId);
    assertThat(usage.get("beneficiary_owner")).isEqualTo("holder-tbp-src");
    assertThat(usage.get("beneficiary_alias")).isEqualTo("Ahorros");
    assertThat(debitCount(result.operationId())).isEqualTo(1);
    assertThat(creditCount(result.operationId())).isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
    String details =
        jdbc.queryForObject(
            "select details from audit_events where action = 'TRANSFER_CONFIRMED'"
                + " and resource_id = ?",
            String.class,
            result.operationId().toString());
    assertThat(details).contains(beneficiaryId.toString());
    assertThat(details).contains("holder-tbp-src");
    assertThat(details).contains("Ahorros");
  }

  @Test
  void conflictCreatesNoAdditionalUsage() {
    UUID first =
        beneficiaries
            .create(new BeneficiaryCommand(destinationId, "Ahorros"), "tbp-cu", sourceActor)
            .beneficiaryId();
    TransferCommand firstCommand = new TransferCommand(sourceId, null, first, 40_00L, "DOP");
    TransferResult confirmed = transfers.transfer(firstCommand, "tbp-conflict", sourceActor);
    assertThat(usageCount(confirmed.operationId())).isEqualTo(1);

    TransferCommand secondCommand =
        new TransferCommand(sourceId, null, UUID.randomUUID(), 40_00L, "DOP");
    assertThatThrownBy(() -> transfers.transfer(secondCommand, "tbp-conflict", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);

    assertThat(usageCount(confirmed.operationId())).isEqualTo(1);
    assertThat(totalUsageRows()).isEqualTo(1);
    assertThat(operationMovements(confirmed.operationId())).isEqualTo(2);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void confirmedReplayDuplicatesNothing() {
    UUID beneficiaryId =
        beneficiaries
            .create(new BeneficiaryCommand(destinationId, "Ahorros"), "tbp-replay", sourceActor)
            .beneficiaryId();
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    TransferResult first = transfers.transfer(command, "tbp-replay-use", sourceActor);

    TransferResult replayed = transfers.transfer(command, "tbp-replay-use", sourceActor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(first.operationId());
    assertThat(usageCount(first.operationId())).isEqualTo(1);
    assertThat(totalUsageRows()).isEqualTo(1);
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
  void directConfirmationCreatesNoUsage() {
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");

    TransferResult result = transfers.transfer(command, "tbp-direct", sourceActor);

    assertThat(result.created()).isTrue();
    assertThat(usageCount(result.operationId())).isZero();
    assertThat(totalUsageRows()).isZero();
  }

  private Map<String, Object> usageRow(UUID operationId) {
    entities.flush();
    return jdbc.queryForMap(
        "select operation_id, beneficiary_id, beneficiary_owner, beneficiary_alias"
            + " from transfer_beneficiary_usage where operation_id = ?",
        operationId);
  }

  private int usageCount(Object operationId) {
    return count(
        "select count(*) from transfer_beneficiary_usage where operation_id = ?", operationId);
  }

  private int totalUsageRows() {
    return count("select count(*) from transfer_beneficiary_usage");
  }

  private Map<String, Object> intentRow(String key) {
    entities.flush();
    return jdbc.queryForMap(
        "select operation_id, outcome, request_hash, source_account_id, beneficiary_id, path,"
            + " amount_minor_units, currency from transfer_operation_idempotency"
            + " where idempotency_key = ?",
        key);
  }

  private int debitCount(UUID operationId) {
    return count(
        "select count(*) from movements where operation_id = ? and direction = 'DEBIT'",
        operationId);
  }

  private int creditCount(UUID operationId) {
    return count(
        "select count(*) from movements where operation_id = ? and direction = 'CREDIT'",
        operationId);
  }

  private UUID creditedAccount(UUID operationId) {
    return jdbc.queryForObject(
        "select account_id from movements where operation_id = ? and direction = 'CREDIT'",
        UUID.class,
        operationId);
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

  private String rejectionAuditDetails(String key) {
    return jdbc.queryForObject(
        "select details from audit_events where action = 'TRANSFER_REJECTED' and resource_id = ?",
        String.class,
        key);
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
