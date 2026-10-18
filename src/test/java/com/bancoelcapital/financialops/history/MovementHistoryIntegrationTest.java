package com.bancoelcapital.financialops.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.financialops.transfer.TransferCommand;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Real-path integration over the whole read stack (ADR-22): the real controller, service,
 * repository, authorization and error handler over H2, driven through MockMvc. It enforces the
 * security verification the mocked slice cannot: the confirmed effects of one account, the generic
 * counterparty, and the byte-for-byte identical 404 for the unknown account and the authenticated
 * non-owner.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:movement-history-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@AutoConfigureMockMvc
@Transactional
class MovementHistoryIntegrationTest {

  @Autowired MockMvc mvc;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired DepositService deposits;

  @Autowired TransferService transfers;

  @Autowired JdbcTemplate jdbc;

  @PersistenceContext EntityManager entities;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;

  @BeforeEach
  void setup() {
    sourceActor = new AuthenticatedActor("holder-mh-src", Set.of());
    var destinationActor = new AuthenticatedActor("holder-mh-dst", Set.of());
    customers.create(new CustomerCreationCommand("holder-mh-src", null), "c-mh", sourceActor);
    customers.create(new CustomerCreationCommand("holder-mh-dst", null), "d-mh", destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand("holder-mh-src", "DOP", "BASIC"), "a-mh", sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand("holder-mh-dst", "DOP", "BASIC"),
                "b-mh",
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-mh", sourceActor);
  }

  @Test
  void holderSeesOwnConfirmedDepositWithNoCounterpartyOrAccountId() throws Exception {
    mvc.perform(
            get("/api/v1/accounts/{id}/movements", sourceId).header("X-Actor-Id", "holder-mh-src"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].operationType").value("DEPOSIT"))
        .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
        .andExpect(jsonPath("$.items[0].amountMinorUnits").value(10000))
        .andExpect(jsonPath("$.items[0].currency").value("DOP"))
        .andExpect(jsonPath("$.nextCursor").value(nullValue()))
        .andExpect(content().string(not(containsString(sourceId.toString()))));
  }

  @Test
  void transferLegsExposeOnlyTheGenericCounterparty() throws Exception {
    transfers.transfer(
        new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), "t-mh", sourceActor);

    MvcResult source =
        mvc.perform(
                get("/api/v1/accounts/{id}/movements", sourceId)
                    .header("X-Actor-Id", "holder-mh-src"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].operationType").value("TRANSFER"))
            .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
            .andExpect(jsonPath("$.items[0].counterparty.type").value("TRANSFER"))
            .andReturn();
    assertThat(source.getResponse().getContentAsString()).doesNotContain(destinationId.toString());

    MvcResult destination =
        mvc.perform(
                get("/api/v1/accounts/{id}/movements", destinationId)
                    .header("X-Actor-Id", "holder-mh-dst"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].operationType").value("TRANSFER"))
            .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
            .andExpect(jsonPath("$.items[0].counterparty.type").value("TRANSFER"))
            .andReturn();
    assertThat(destination.getResponse().getContentAsString()).doesNotContain(sourceId.toString());
  }

  @Test
  void unknownAccountAndAuthenticatedNonOwnerReturnByteIdentical404() throws Exception {
    MvcResult unknown =
        mvc.perform(
                get("/api/v1/accounts/{id}/movements", UUID.randomUUID())
                    .header("X-Actor-Id", "holder-mh-src"))
            .andExpect(status().isNotFound())
            .andReturn();
    MvcResult nonOwner =
        mvc.perform(
                get("/api/v1/accounts/{id}/movements", destinationId)
                    .header("X-Actor-Id", "holder-mh-src"))
            .andExpect(status().isNotFound())
            .andReturn();

    assertThat(nonOwner.getResponse().getStatus()).isEqualTo(404);
    assertThat(nonOwner.getResponse().getContentAsString())
        .isEqualTo(unknown.getResponse().getContentAsString());
    assertThat(nonOwner.getResponse().getContentType())
        .isEqualTo(unknown.getResponse().getContentType())
        .isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    assertThat(nonOwner.getResponse().getContentAsString()).doesNotContain("holder-mh-src");
  }

  @Test
  void unauthenticatedActorOnExistingAccountIs401() throws Exception {
    mvc.perform(get("/api/v1/accounts/{id}/movements", sourceId))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void keysetPaginationTraversesTheStaticSetWithoutDuplicatesOrOmissions() throws Exception {
    for (int i = 0; i < 5; i++) {
      deposits.deposit(
          new DepositCommand(sourceId, (i + 1) * 100L, "DOP"), "p-mh-" + i, sourceActor);
    }
    entities.flush();
    int expected = movementCount(sourceId);

    List<String> collected = new ArrayList<>();
    List<Instant> ordered = new ArrayList<>();
    String cursor = null;
    do {
      var request = get("/api/v1/accounts/{id}/movements", sourceId).param("limit", "2");
      if (cursor != null) {
        request = request.param("cursor", cursor);
      }
      MvcResult page =
          mvc.perform(request.header("X-Actor-Id", "holder-mh-src"))
              .andExpect(status().isOk())
              .andReturn();
      String body = page.getResponse().getContentAsString();
      JsonNode parsed = parse(body);
      parsed.get("items").forEach(item -> collected.add(item.get("movementId").asText()));
      parsed
          .get("items")
          .forEach(item -> ordered.add(Instant.parse(item.get("createdAt").asText())));
      cursor = parsed.get("nextCursor").isNull() ? null : parsed.get("nextCursor").asText();
    } while (cursor != null);

    assertThat(collected).hasSize(expected);
    assertThat(new HashSet<>(collected)).hasSameSizeAs(collected);
    assertThat(ordered).isSortedAccordingTo(Comparator.reverseOrder());
  }

  @Test
  void readIsNotAuditedAndDoesNotMutateBalances() throws Exception {
    entities.flush();
    int auditBefore = count("select count(*) from audit_events");
    long balanceBefore = balanceOf(sourceId);

    mvc.perform(
            get("/api/v1/accounts/{id}/movements", sourceId).header("X-Actor-Id", "holder-mh-src"))
        .andExpect(status().isOk());

    entities.flush();
    assertThat(count("select count(*) from audit_events")).isEqualTo(auditBefore);
    assertThat(balanceOf(sourceId)).isEqualTo(balanceBefore);
  }

  private static JsonNode parse(String body) {
    try {
      return new ObjectMapper().readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException("unparseable response body", e);
    }
  }

  private int movementCount(UUID accountId) {
    Integer total =
        jdbc.queryForObject(
            "select count(*) from movements where account_id = ?", Integer.class, accountId);
    return total == null ? 0 : total;
  }

  private int count(String sql) {
    Integer total = jdbc.queryForObject(sql, Integer.class);
    return total == null ? 0 : total;
  }

  private long balanceOf(UUID accountId) {
    Long balance =
        jdbc.queryForObject(
            "select balance_minor_units from accounts where id = ?", Long.class, accountId);
    return balance == null ? 0L : balance;
  }
}
