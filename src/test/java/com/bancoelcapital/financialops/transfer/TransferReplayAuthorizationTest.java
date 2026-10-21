package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
@AutoConfigureMockMvc
@Transactional
class TransferReplayAuthorizationTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired BeneficiaryService beneficiaries;

  @Autowired JdbcTemplate jdbc;

  @Autowired MockMvc mvc;

  @PersistenceContext EntityManager entities;

  private Logger transferLogger;
  private ListAppender<ILoggingEvent> appender;

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

  @BeforeEach
  void attachTransferLogAppender() {
    transferLogger = (Logger) LoggerFactory.getLogger(TransferService.class);
    appender = new ListAppender<>();
    appender.start();
    transferLogger.addAppender(appender);
    transferLogger.setLevel(Level.WARN);
  }

  @AfterEach
  void detachTransferLogAppender() {
    transferLogger.detachAppender(appender);
    appender.stop();
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
  void unknownSourceRejectionRetryFailsClosedWithoutReplay() {
    UUID unknownSource = UUID.randomUUID();
    String key = "tra-unknown-source";
    TransferCommand command = new TransferCommand(unknownSource, destinationId, 40_00L, "DOP");

    assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);
    Map<String, Object> before = intentRow(key);
    assertThat(before.get("outcome")).isEqualTo("REJECTED");

    assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    assertThat(intentRow(key)).isEqualTo(before);
    assertThat(count("select count(*) from financial_operations where operation_type = 'TRANSFER'"))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from movements where operation_id = ?",
                before.get("operation_id")))
        .isZero();
    assertThat(count("select count(*) from transfer_beneficiary_usage")).isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_REJECTED'"
                    + " and resource_id = ?",
                key))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT'"
                    + " and resource_id = ?",
                key))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
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

  /**
   * Contrato de {@code operationId}: una fila CONFIRMED del camino directo sin referencia de
   * operación atraviesa la autorización real contra la fuente persistida y la comparación real de
   * intención, pero el replay falla cerrado en lugar de devolver un éxito sin identificador. No se
   * crea ninguna operación, movimiento ni auditoría nueva.
   */
  @Test
  void confirmedDirectReplayWithoutOperationIdFailsClosed() {
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    entities.persist(
        new TransferOperationIdempotency(
            "tra-no-op",
            null,
            TransferService.requestHash(command),
            TransferOperationOutcome.CONFIRMED,
            sourceId,
            null,
            "direct",
            40_00L,
            "DOP"));
    entities.flush();

    Map<String, Object> stored = intentRow("tra-no-op");
    assertThat(stored.get("outcome")).isEqualTo("CONFIRMED");
    assertThat(stored.get("operation_id")).isNull();

    assertThatThrownBy(() -> transfers.transfer(command, "tra-no-op", sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FAILED);

    entities.flush();
    assertThat(count("select count(*) from financial_operations where operation_type = 'TRANSFER'"))
        .isZero();
    assertThat(
            count(
                "select count(*) from movements m join financial_operations o"
                    + " on o.id = m.operation_id where o.operation_type = 'TRANSFER'"))
        .isZero();
    assertThat(count("select count(*) from audit_events where resource_id = ?", "tra-no-op"))
        .isZero();
    assertThat(intentRow("tra-no-op")).isEqualTo(stored);
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  /**
   * Contrato de {@code operationId}: una fila CONFIRMED con una referencia no nula que no existe
   * tampoco puede presentarse como recuperación exitosa. El replay falla cerrado, no crea efectos
   * nuevos y el GET mantiene su 404 existente.
   */
  @Test
  void confirmedDirectReplayWithDanglingNonNullOperationIdFailsClosed() throws Exception {
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    String hash = TransferService.requestHash(command);
    UUID danglingOperationId = UUID.randomUUID();
    entities.persist(
        new TransferOperationIdempotency(
            "tra-dangling-op",
            danglingOperationId,
            hash,
            TransferOperationOutcome.CONFIRMED,
            sourceId,
            null,
            "direct",
            40_00L,
            "DOP"));
    entities.flush();
    Map<String, Object> before = intentRow("tra-dangling-op");

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceAccountId\":\""
                        + sourceId
                        + "\",\"destinationAccountId\":\""
                        + destinationId
                        + "\",\"amountMinorUnits\":4000,\"currency\":\"DOP\"}")
                .header("Idempotency-Key", "tra-dangling-op")
                .header("X-Actor-Id", sourceActor.subject()))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.operationId").doesNotExist());

    mvc.perform(
            get("/api/v1/transfer-operations/tra-dangling-op")
                .header("Idempotency-Key-Hash", hash)
                .header("X-Actor-Id", sourceActor.subject()))
        .andExpect(status().isNotFound());

    entities.flush();
    assertThat(intentRow("tra-dangling-op")).isEqualTo(before);
    assertThat(count("select count(*) from financial_operations where operation_type = 'TRANSFER'"))
        .isZero();
    assertThat(
            count(
                "select count(*) from movements m join financial_operations o"
                    + " on o.id = m.operation_id where o.operation_type = 'TRANSFER'"))
        .isZero();
    assertThat(count("select count(*) from audit_events where resource_id = ?", "tra-dangling-op"))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  /**
   * H-02 ordering: authorization against the persisted source wins over corruption detection. A
   * foreign actor keeps the ordinary 403 and never learns that the row references a missing
   * operation; the guard does not turn denial into a 500.
   */
  @Test
  void confirmedDanglingOperationReplayFromForeignActorIsDenied() throws Exception {
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    String key = "tra-dangling-foreign";
    UUID danglingOperationId = UUID.randomUUID();
    entities.persist(
        new TransferOperationIdempotency(
            key,
            danglingOperationId,
            TransferService.requestHash(command),
            TransferOperationOutcome.CONFIRMED,
            sourceId,
            null,
            "direct",
            40_00L,
            "DOP"));
    entities.flush();
    Map<String, Object> before = intentRow(key);

    postTransfer(key, otherActor.subject(), 40_00L)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());

    entities.flush();
    assertThat(intentRow(key)).isEqualTo(before);
    assertThat(count("select count(*) from audit_events where resource_id = ?", key)).isZero();
    assertNoNewTransferEffects();
  }

  /**
   * H-02 ordering: a different intent must not reach the conflict path while the confirmed
   * operation reference is dangling. The response is the existing generic 500, no TRANSFER_CONFLICT
   * evidence is recorded, and the anomaly logs one stable event without identifiers or payload.
   */
  @Test
  void confirmedDanglingOperationReplayWithDifferentIntentFailsClosedWithoutConflict()
      throws Exception {
    TransferCommand storedCommand = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    String key = "tra-dangling-mismatch";
    UUID danglingOperationId = UUID.randomUUID();
    entities.persist(
        new TransferOperationIdempotency(
            key,
            danglingOperationId,
            TransferService.requestHash(storedCommand),
            TransferOperationOutcome.CONFIRMED,
            sourceId,
            null,
            "direct",
            40_00L,
            "DOP"));
    entities.flush();
    Map<String, Object> before = intentRow(key);

    postTransfer(key, sourceActor.subject(), 50_00L)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.operationId").doesNotExist());

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getLoggerName()).isEqualTo(TransferService.class.getName());
    assertThat(event.getFormattedMessage())
        .isEqualTo("transfer.recovery.confirmed_operation_missing");
    assertThat(event.getArgumentArray()).isNullOrEmpty();
    assertThat(event.getThrowableProxy()).isNull();
    assertThat(event.getFormattedMessage())
        .doesNotContain(
            key,
            danglingOperationId.toString(),
            sourceActor.subject(),
            sourceId.toString(),
            "4000",
            "5000",
            "DOP");

    entities.flush();
    assertThat(intentRow(key)).isEqualTo(before);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_CONFLICT'"
                    + " and resource_id = ?",
                key))
        .isZero();
    assertThat(count("select count(*) from audit_events where resource_id = ?", key)).isZero();
    assertNoNewTransferEffects();
  }

  /**
   * H-02 beneficiary special case: without the usage snapshot the beneficiary owner cannot be
   * authorized, so the persisted row stays an ordinary 403. The anomaly is not disclosed and the
   * guard, which runs after authorization, is intentionally not reached.
   */
  @Test
  void confirmedDanglingBeneficiaryOperationWithoutUsageStaysForbidden() {
    UUID beneficiaryId = UUID.randomUUID();
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    String key = "tra-dangling-beneficiary";
    entities.persist(
        new TransferOperationIdempotency(
            key,
            UUID.randomUUID(),
            TransferService.requestHash(command),
            TransferOperationOutcome.CONFIRMED,
            sourceId,
            beneficiaryId,
            "beneficiary-transfer",
            40_00L,
            "DOP"));
    entities.flush();
    Map<String, Object> before = intentRow(key);

    assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    entities.flush();
    assertThat(intentRow(key)).isEqualTo(before);
    assertThat(count("select count(*) from transfer_beneficiary_usage")).isZero();
    assertThat(count("select count(*) from audit_events where resource_id = ?", key)).isZero();
    assertThat(appender.list).isEmpty();
    assertNoNewTransferEffects();
  }

  /**
   * GET correlacionado sobre una fila sin referencia de operación: la consulta no llega al
   * repositorio de operaciones con un id nulo (lo que produciría una excepción no controlada),
   * respeta la autorización vigente sobre la fuente persistida y devuelve el 404 del contrato
   * existente a un titular autorizado.
   */
  @Test
  void queryWithMissingOperationReferenceReturnsControlledNotFound() throws Exception {
    TransferCommand command = new TransferCommand(sourceId, destinationId, 40_00L, "DOP");
    String hash = TransferService.requestHash(command);
    entities.persist(
        new TransferOperationIdempotency(
            "tra-no-op-query",
            null,
            hash,
            TransferOperationOutcome.CONFIRMED,
            sourceId,
            null,
            "direct",
            40_00L,
            "DOP"));
    entities.flush();

    mvc.perform(
            get("/api/v1/transfer-operations/tra-no-op-query")
                .header("Idempotency-Key-Hash", hash)
                .header("X-Actor-Id", sourceActor.subject()))
        .andExpect(status().isNotFound());

    mvc.perform(
            get("/api/v1/transfer-operations/tra-no-op-query")
                .header("Idempotency-Key-Hash", hash)
                .header("X-Actor-Id", otherActor.subject()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());
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

  private org.springframework.test.web.servlet.ResultActions postTransfer(
      String key, String actorHeader, long amountMinorUnits) throws Exception {
    return mvc.perform(
        post("/api/v1/transfers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"sourceAccountId\":\""
                    + sourceId
                    + "\",\"destinationAccountId\":\""
                    + destinationId
                    + "\",\"amountMinorUnits\":"
                    + amountMinorUnits
                    + ",\"currency\":\"DOP\"}")
            .header("Idempotency-Key", key)
            .header("X-Actor-Id", actorHeader));
  }

  private void assertNoNewTransferEffects() {
    assertThat(count("select count(*) from financial_operations where operation_type = 'TRANSFER'"))
        .isZero();
    assertThat(
            count(
                "select count(*) from movements m join financial_operations o"
                    + " on o.id = m.operation_id where o.operation_type = 'TRANSFER'"))
        .isZero();
    assertThat(count("select count(*) from transfer_beneficiary_usage")).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
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
