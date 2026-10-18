package com.bancoelcapital.financialops.history.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.bancoelcapital.financialops.core.FinancialOperationType;
import com.bancoelcapital.financialops.core.MovementDirection;
import com.bancoelcapital.financialops.history.MovementHistoryEntry;
import com.bancoelcapital.financialops.history.MovementHistoryException;
import com.bancoelcapital.financialops.history.MovementHistoryPage;
import com.bancoelcapital.financialops.history.MovementHistoryService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Web-slice mapping only (ADR-22), with the history service mocked. This proves HTTP shape, status
 * mapping and input validation but NOT the real authorization path; the byte-level 404 equality is
 * enforced by {@code MovementHistoryIntegrationTest}.
 */
@WebMvcTest(MovementHistoryController.class)
class MovementHistoryControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean MovementHistoryService service;

  private final UUID accountId = UUID.randomUUID();

  @Test
  void returns200WithDocumentedShapeAndNullCounterpartyForDeposit() throws Exception {
    MovementHistoryEntry entry = depositEntry();
    when(service.history(eq(accountId), anyInt(), any(), any(AuthenticatedActor.class)))
        .thenReturn(new MovementHistoryPage(List.of(entry), "next-token"));

    mvc.perform(get("/api/v1/accounts/{id}/movements", accountId).header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].movementId").value(entry.movementId().toString()))
        .andExpect(jsonPath("$.items[0].operationId").value(entry.operationId().toString()))
        .andExpect(jsonPath("$.items[0].operationType").value("DEPOSIT"))
        .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
        .andExpect(jsonPath("$.items[0].amountMinorUnits").value(100))
        .andExpect(jsonPath("$.items[0].currency").value("DOP"))
        .andExpect(jsonPath("$.items[0].createdAt").exists())
        .andExpect(jsonPath("$.items[0].counterparty").value(nullValue()))
        .andExpect(jsonPath("$.items[0].accountId").doesNotExist())
        .andExpect(jsonPath("$.nextCursor").value("next-token"))
        .andExpect(content().string(not(containsString(accountId.toString()))));
  }

  @Test
  void transferExposesOnlyTheGenericCounterpartyDescriptor() throws Exception {
    MovementHistoryEntry entry =
        new MovementHistoryEntry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            FinancialOperationType.TRANSFER,
            MovementDirection.DEBIT,
            250L,
            "DOP",
            Instant.now());
    when(service.history(eq(accountId), anyInt(), any(), any(AuthenticatedActor.class)))
        .thenReturn(new MovementHistoryPage(List.of(entry), null));

    mvc.perform(get("/api/v1/accounts/{id}/movements", accountId).header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].operationType").value("TRANSFER"))
        .andExpect(jsonPath("$.items[0].counterparty.type").value("TRANSFER"))
        .andExpect(jsonPath("$.items[0].counterparty.accountId").doesNotExist())
        .andExpect(jsonPath("$.items[0].accountId").doesNotExist())
        .andExpect(jsonPath("$.nextCursor").value(nullValue()));
  }

  @Test
  void emptyHistoryReturnsEmptyItems() throws Exception {
    when(service.history(eq(accountId), anyInt(), any(), any(AuthenticatedActor.class)))
        .thenReturn(new MovementHistoryPage(List.of(), null));

    mvc.perform(get("/api/v1/accounts/{id}/movements", accountId).header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.nextCursor").value(nullValue()));
  }

  @Test
  void rejectsOutOfRangeAndNonNumericLimitsWithoutTouchingTheService() throws Exception {
    mvc.perform(
            get("/api/v1/accounts/{id}/movements", accountId)
                .param("limit", "0")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/accounts/{id}/movements", accountId)
                .param("limit", "101")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/accounts/{id}/movements", accountId)
                .param("limit", "abc")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsMalformedCursorViaTheServiceContract() throws Exception {
    when(service.history(eq(accountId), anyInt(), any(), any(AuthenticatedActor.class)))
        .thenThrow(
            new MovementHistoryException(
                MovementHistoryException.Kind.INVALID, "malformed cursor"));

    mvc.perform(
            get("/api/v1/accounts/{id}/movements", accountId)
                .param("cursor", "not-a-token")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unauthenticatedActorOnExistingAccountIs401() throws Exception {
    when(service.history(eq(accountId), anyInt(), any(), any(AuthenticatedActor.class)))
        .thenThrow(
            new MovementHistoryException(
                MovementHistoryException.Kind.UNAUTHENTICATED, "unauthenticated actor"));

    mvc.perform(get("/api/v1/accounts/{id}/movements", accountId))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void notFoundMappingIsIdenticalForUnknownAndUnauthorized() throws Exception {
    when(service.history(any(), anyInt(), any(), any(AuthenticatedActor.class)))
        .thenThrow(
            new MovementHistoryException(MovementHistoryException.Kind.NOT_FOUND, "not found"));

    MvcResult unknown =
        mvc.perform(
                get("/api/v1/accounts/{id}/movements", UUID.randomUUID())
                    .header("X-Actor-Id", "holder-1"))
            .andExpect(status().isNotFound())
            .andReturn();
    MvcResult stranger =
        mvc.perform(
                get("/api/v1/accounts/{id}/movements", UUID.randomUUID())
                    .header("X-Actor-Id", "holder-2"))
            .andExpect(status().isNotFound())
            .andReturn();

    assertThat(stranger.getResponse().getContentAsString())
        .isEqualTo(unknown.getResponse().getContentAsString());
    assertThat(stranger.getResponse().getContentType())
        .isEqualTo(unknown.getResponse().getContentType())
        .isEqualTo(MediaType.APPLICATION_JSON_VALUE);
  }

  private static MovementHistoryEntry depositEntry() {
    return new MovementHistoryEntry(
        UUID.randomUUID(),
        UUID.randomUUID(),
        FinancialOperationType.DEPOSIT,
        MovementDirection.CREDIT,
        100L,
        "DOP",
        Instant.parse("2026-01-01T00:00:00.000001Z"));
  }
}
