package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * H-01: authentication must be checked before deterministic rejection, account/idempotency/
 * operation lookups, persistence or audit. Unauthenticated requests must be indistinguishable for
 * known, unknown and same-account sources: 401 with no transfer evidence and unchanged balances.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-unauthenticated;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@AutoConfigureMockMvc
@Transactional
class TransferUnauthenticatedOrderingTest {

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  @Autowired MockMvc mvc;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    sourceActor = new AuthenticatedActor("holder-tuo-src-" + tag, Set.of());
    destinationActor = new AuthenticatedActor("holder-tuo-dst-" + tag, Set.of());

    customers.create(
        new CustomerCreationCommand(sourceActor.subject(), null), "c-tuo-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationActor.subject(), null),
        "d-tuo-" + tag,
        destinationActor);

    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-tuo-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationActor.subject(), "DOP", "BASIC"),
                "b-tuo-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tuo-" + tag, sourceActor);
  }

  @Test
  void unauthenticatedKnownSourceAndDestinationReturns401WithoutEffects() throws Exception {
    String key = "tuo-known-" + tag;

    postTransfer(sourceOf(sourceId), destinationOf(destinationId), key, null)
        .andExpect(status().isUnauthorized());

    assertNoTransferEvidence(key);
  }

  @Test
  void unauthenticatedUnknownSourceReturns401WithoutEffects() throws Exception {
    String key = "tuo-unknown-source-" + tag;

    postTransfer(sourceOf(UUID.randomUUID()), destinationOf(destinationId), key, null)
        .andExpect(status().isUnauthorized());

    assertNoTransferEvidence(key);
  }

  @Test
  void unauthenticatedSameAccountReturns401WithoutEffects() throws Exception {
    String key = "tuo-same-" + tag;

    postTransfer(sourceOf(sourceId), destinationOf(sourceId), key, null)
        .andExpect(status().isUnauthorized());

    assertNoTransferEvidence(key);
  }

  @Test
  void unauthenticatedUnknownDestinationReturns401WithoutEffects() throws Exception {
    String key = "tuo-unknown-destination-" + tag;

    postTransfer(sourceOf(sourceId), destinationOf(UUID.randomUUID()), key, null)
        .andExpect(status().isUnauthorized());

    assertNoTransferEvidence(key);
  }

  @Test
  void blankActorHeaderReturns401WithoutEffects() throws Exception {
    String key = "tuo-blank-" + tag;

    postTransfer(sourceOf(sourceId), destinationOf(destinationId), key, "   ")
        .andExpect(status().isUnauthorized());

    assertNoTransferEvidence(key);
  }

  @Test
  void authenticatedForeignSourceStillReturns403WithoutEffects() throws Exception {
    String key = "tuo-foreign-" + tag;

    postTransfer(sourceOf(sourceId), destinationOf(destinationId), key, destinationActor.subject())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("transfer not authorized"));

    assertNoTransferEvidence(key);
  }

  @Test
  void authenticatedUnknownSourceReturnsTheSame403WithoutEffects() throws Exception {
    String key = "tuo-auth-unknown-" + tag;

    postTransfer(
            sourceOf(UUID.randomUUID()), destinationOf(destinationId), key, sourceActor.subject())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("transfer not authorized"));

    assertNoTransferEvidence(key);
  }

  @Test
  void unauthorizedSourcePrecedesUnknownDestinationBusinessRejection() throws Exception {
    String key = "tuo-foreign-unknown-dest-" + tag;

    postTransfer(
            sourceOf(sourceId), destinationOf(UUID.randomUUID()), key, destinationActor.subject())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("transfer not authorized"));

    assertNoTransferEvidence(key);
  }

  @Test
  void unauthorizedAttemptDoesNotPoisonTheKeyForALaterAuthorizedRequest() throws Exception {
    String key = "tuo-key-reuse-" + tag;

    postTransfer(sourceOf(sourceId), destinationOf(destinationId), key, destinationActor.subject())
        .andExpect(status().isForbidden());
    assertNoTransferEvidence(key);

    postTransfer(sourceOf(sourceId), destinationOf(destinationId), key, sourceActor.subject())
        .andExpect(status().isCreated());

    assertThat(idempotencyOutcome(key)).isEqualTo("CONFIRMED");
    assertThat(transferOperationCount()).isEqualTo(1);
    assertThat(movementCount()).isEqualTo(2);
    assertThat(auditCount(key)).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  @Test
  void authenticatedKnownSourceStillConfirms() throws Exception {
    String key = "tuo-auth-confirm-" + tag;

    postTransfer(sourceOf(sourceId), destinationOf(destinationId), key, sourceActor.subject())
        .andExpect(status().isCreated());

    assertThat(idempotencyOutcome(key)).isEqualTo("CONFIRMED");
    assertThat(transferOperationCount()).isEqualTo(1);
    assertThat(movementCount()).isEqualTo(2);
    assertThat(auditCount(key)).isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  private org.springframework.test.web.servlet.ResultActions postTransfer(
      String source, String destination, String key, String actorHeader) throws Exception {
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

  private String sourceOf(UUID accountId) {
    return accountId.toString();
  }

  private String destinationOf(UUID accountId) {
    return accountId.toString();
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
