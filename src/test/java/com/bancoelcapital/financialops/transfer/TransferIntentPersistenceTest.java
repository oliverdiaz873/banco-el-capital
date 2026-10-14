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
 * Writer-boundary intent persistence through the real TransferService (ADR-21, Phase 3). Proves
 * CONFIRMED persists complete direct intent plus two movements plus audit, REJECTED persists intent
 * plus rejection evidence with zero movements and intact balances, and replay leaves the stored
 * intent row untouched with no new effects. Hash, replay, and conflict behavior are unchanged.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-intent;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@Transactional
class TransferIntentPersistenceTest {

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
    String sourceHolder = "holder-tiw-src";
    String destinationHolder = "holder-tiw-dst";
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    var destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-tiw", sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "d-tiw", destinationActor);
    sourceId =
        accounts
            .create(new AccountCreationCommand(sourceHolder, "DOP", "BASIC"), "a-tiw", sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "b-tiw",
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tiw", sourceActor);
  }

  @Test
  void confirmPersistsCompleteIntentMovementsAndAudit() {
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");

    TransferResult result = transfers.transfer(command, "ti-confirm", sourceActor);

    assertThat(result.created()).isTrue();
    Map<String, Object> row = intentRow("ti-confirm");
    assertThat(row.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(row.get("operation_id")).isEqualTo(result.operationId());
    assertThat(row.get("source_account_id")).isEqualTo(sourceId);
    assertThat(row.get("beneficiary_id")).isNull();
    assertThat(row.get("path")).isEqualTo("direct");
    assertThat(row.get("amount_minor_units")).isEqualTo(40_00L);
    assertThat(row.get("currency")).isEqualTo("DOP");
    assertThat(row.get("request_hash")).isEqualTo(TransferService.requestHash(command));
    assertThat(debitCount(result.operationId())).isEqualTo(1);
    assertThat(creditCount(result.operationId())).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                    + " and resource_id = ?",
                result.operationId().toString()))
        .isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void rejectUnknownDestinationPersistsIntentWithoutMovements() {
    UUID unknown = UUID.randomUUID();
    TransferCommand command = new TransferCommand(sourceId, unknown, 40_00L, "DOP");

    assertThatThrownBy(() -> transfers.transfer(command, "ti-reject", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    Map<String, Object> row = intentRow("ti-reject");
    assertThat(row.get("outcome")).isEqualTo("REJECTED");
    assertThat(row.get("source_account_id")).isEqualTo(sourceId);
    assertThat(row.get("beneficiary_id")).isNull();
    assertThat(row.get("path")).isEqualTo("direct");
    assertThat(row.get("amount_minor_units")).isEqualTo(40_00L);
    assertThat(row.get("currency")).isEqualTo("DOP");
    assertThat(
            count(
                "select count(*) from movements m join financial_operations o on o.id ="
                    + " m.operation_id where o.id = ?",
                row.get("operation_id")))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_REJECTED'"
                    + " and resource_id = ?",
                "ti-reject"))
        .isEqualTo(1);
  }

  @Test
  void replayLeavesIntentRowUntouched() {
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    TransferResult first = transfers.transfer(command, "ti-replay", sourceActor);
    Map<String, Object> before = intentRow("ti-replay");
    int auditsBefore =
        count(
            "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                + " and resource_id = ?",
            first.operationId().toString());

    TransferResult replayed = transfers.transfer(command, "ti-replay", sourceActor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(first.operationId());
    assertThat(intentRow("ti-replay")).isEqualTo(before);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"
                    + " and resource_id = ?",
                first.operationId().toString()))
        .isEqualTo(auditsBefore);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  private Map<String, Object> intentRow(String key) {
    // Native SQL bypasses Hibernate auto-flush (unlike the explicit flush in the confirm path),
    // so flush the persistence context first to observe uncommitted effects deterministically.
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

  private int count(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
