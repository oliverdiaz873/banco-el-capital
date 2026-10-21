package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * H-01/H-02 authorization parity on real PostgreSQL: unauthenticated POST is rejected before any
 * deterministic rejection or lookup, while authenticated GET keeps its owner/foreign/missing
 * contract. PostgreSQL is required here because the production schema, FK and enum behavior are not
 * equivalent to H2.
 */
@AutoConfigureMockMvc
@Transactional
class TransferPostgresAuthorizationTest extends AbstractPostgresTest {

  @Autowired MockMvc mvc;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    sourceActor = new AuthenticatedActor("holder-tpa-src-" + tag, Set.of());
    destinationActor = new AuthenticatedActor("holder-tpa-dst-" + tag, Set.of());

    customers.create(
        new CustomerCreationCommand(sourceActor.subject(), null), "c-tpa-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationActor.subject(), null),
        "d-tpa-" + tag,
        destinationActor);

    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-tpa-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationActor.subject(), "DOP", "BASIC"),
                "b-tpa-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tpa-" + tag, sourceActor);
  }

  @Test
  void unauthenticatedPostOnKnownSourceReturns401WithoutEffects() throws Exception {
    String key = "tpa-known-" + tag;

    postTransfer(sourceId, destinationId, key, null).andExpect(status().isUnauthorized());

    assertNoTransferEvidence(key);
  }

  @Test
  void unauthenticatedPostOnUnknownSourceReturns401WithoutEffects() throws Exception {
    String key = "tpa-unknown-" + tag;

    postTransfer(UUID.randomUUID(), destinationId, key, null).andExpect(status().isUnauthorized());

    assertNoTransferEvidence(key);
  }

  @Test
  void unauthenticatedPostOnSameAccountReturns401WithoutEffects() throws Exception {
    String key = "tpa-same-" + tag;

    postTransfer(sourceId, sourceId, key, null).andExpect(status().isUnauthorized());

    assertNoTransferEvidence(key);
  }

  @Test
  void blankActorHeaderReturns401WithoutEffects() throws Exception {
    String key = "tpa-blank-" + tag;

    postTransfer(sourceId, destinationId, key, "   ").andExpect(status().isUnauthorized());

    assertNoTransferEvidence(key);
  }

  @Test
  void authenticatedForeignSourceStillReturns403WithoutEffects() throws Exception {
    String key = "tpa-foreign-" + tag;

    postTransfer(sourceId, destinationId, key, destinationActor.subject())
        .andExpect(status().isForbidden());

    assertNoTransferEvidence(key);
  }

  @Test
  void authenticatedKnownSourceStillConfirms() throws Exception {
    String key = "tpa-confirm-" + tag;

    postTransfer(sourceId, destinationId, key, sourceActor.subject())
        .andExpect(status().isCreated());

    assertThat(idempotencyOutcome(key)).isEqualTo("CONFIRMED");
    assertThat(transferOperationCount()).isEqualTo(1);
    assertThat(movementCount()).isEqualTo(2);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void getParityKeepsOwnerForeignMissingAndHashMismatchContract() throws Exception {
    String key = "tpa-get-" + tag;

    postTransfer(sourceId, destinationId, key, sourceActor.subject())
        .andExpect(status().isCreated());

    UUID operationId =
        jdbc.queryForObject(
            "select operation_id from transfer_operation_idempotency where idempotency_key = ?",
            UUID.class,
            key);
    String hash =
        jdbc.queryForObject(
            "select request_hash from transfer_operation_idempotency where idempotency_key = ?",
            String.class,
            key);

    mvc.perform(
            get("/api/v1/transfer-operations/{key}", key)
                .header("Idempotency-Key-Hash", hash)
                .header("X-Actor-Id", sourceActor.subject()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationId").value(operationId.toString()));

    mvc.perform(
            get("/api/v1/transfer-operations/{key}", key)
                .header("Idempotency-Key-Hash", hash)
                .header("X-Actor-Id", destinationActor.subject()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());

    mvc.perform(
            get("/api/v1/transfer-operations/{key}", "tpa-missing-" + tag)
                .header("Idempotency-Key-Hash", hash)
                .header("X-Actor-Id", sourceActor.subject()))
        .andExpect(status().isNotFound());

    mvc.perform(
            get("/api/v1/transfer-operations/{key}", key)
                .header("Idempotency-Key-Hash", "wrong-hash")
                .header("X-Actor-Id", sourceActor.subject()))
        .andExpect(status().isNotFound());

    mvc.perform(get("/api/v1/transfer-operations/{key}", key).header("Idempotency-Key-Hash", hash))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getWithMissingOperationReferenceAuthorizesThenReturns404() throws Exception {
    String key = "tpa-no-op-" + tag;
    String hash = "tpa-no-op-hash-" + tag;

    jdbc.update(
        "insert into transfer_operation_idempotency"
            + " (idempotency_key, operation_id, request_hash, outcome, source_account_id,"
            + " beneficiary_id, path, amount_minor_units, currency, created_at)"
            + " values (?, null, ?, 'CONFIRMED', ?, null, 'direct', ?, 'DOP', now())",
        key,
        hash,
        sourceId,
        40_00L);

    mvc.perform(
            get("/api/v1/transfer-operations/{key}", key)
                .header("Idempotency-Key-Hash", hash)
                .header("X-Actor-Id", sourceActor.subject()))
        .andExpect(status().isNotFound());

    mvc.perform(
            get("/api/v1/transfer-operations/{key}", key)
                .header("Idempotency-Key-Hash", hash)
                .header("X-Actor-Id", destinationActor.subject()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());
  }

  private org.springframework.test.web.servlet.ResultActions postTransfer(
      UUID source, UUID destination, String key, String actorHeader) throws Exception {
    var request =
        post("/api/v1/transfers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"sourceAccountId\":\""
                    + source
                    + "\",\"destinationAccountId\":\""
                    + destination
                    + "\",\"amountMinorUnits\":4000,\"currency\":\"DOP\"}")
            .header("Idempotency-Key", key);
    if (actorHeader != null) {
      request.header("X-Actor-Id", actorHeader);
    }
    return mvc.perform(request);
  }

  private void assertNoTransferEvidence(String key) {
    assertThat(idempotencyOutcome(key)).isNull();
    assertThat(transferOperationCount()).isZero();
    assertThat(movementCount()).isZero();
    assertThat(auditCount(key)).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  private String idempotencyOutcome(String key) {
    var outcomes =
        jdbc.queryForList(
            "select outcome from transfer_operation_idempotency where idempotency_key = ?",
            String.class,
            key);
    return outcomes.isEmpty() ? null : outcomes.get(0);
  }

  private int transferOperationCount() {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from financial_operations where operation_type = 'TRANSFER'"
                + " and account_id in (?, ?)",
            Integer.class,
            sourceId,
            destinationId);
    return count == null ? 0 : count;
  }

  private int movementCount() {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from movements m join financial_operations o on o.id = m.operation_id"
                + " where o.operation_type = 'TRANSFER' and o.account_id in (?, ?)",
            Integer.class,
            sourceId,
            destinationId);
    return count == null ? 0 : count;
  }

  private int auditCount(String key) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from audit_events where action like 'TRANSFER_%' and resource_id = ?",
            Integer.class, key);
    return count == null ? 0 : count;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
