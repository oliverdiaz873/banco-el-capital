package com.bancoelcapital.beneficiaries.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bancoelcapital.beneficiaries.internal.BeneficiaryCreationOutcome;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryException;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryOutcome;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryResult;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryService;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryView;
import com.bancoelcapital.identity.AuthenticatedActor;

@WebMvcTest(BeneficiaryController.class)
class BeneficiaryControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean BeneficiaryService service;

  UUID destinationId = UUID.randomUUID();
  String body = "{\"destinationAccountId\":\"" + destinationId + "\",\"alias\":\"Ahorros\"}";

  @Test
  void returns201OnConfirmation() throws Exception {
    UUID beneficiaryId = UUID.randomUUID();
    when(service.create(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new BeneficiaryResult(beneficiaryId, true));

    mvc.perform(
            post("/api/v1/beneficiaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "owner-1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.beneficiaryId").value(beneficiaryId.toString()))
        .andExpect(jsonPath("$.created").value(true));
  }

  @Test
  void returns200OnReplay() throws Exception {
    UUID beneficiaryId = UUID.randomUUID();
    when(service.create(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new BeneficiaryResult(beneficiaryId, false));

    mvc.perform(
            post("/api/v1/beneficiaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "owner-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(false));
  }

  @Test
  void missingIdempotencyKeyIsBadRequest() throws Exception {
    mvc.perform(
            post("/api/v1/beneficiaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Actor-Id", "owner-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mapsConflictRejectedForbiddenUnauthorizedAndUnknown() throws Exception {
    when(service.create(any(), eq("c"), any(AuthenticatedActor.class)))
        .thenThrow(new BeneficiaryException(BeneficiaryException.Kind.CONFLICT, "x"));
    when(service.create(any(), eq("r"), any(AuthenticatedActor.class)))
        .thenThrow(new BeneficiaryException(BeneficiaryException.Kind.REJECTED, "y"));
    when(service.create(any(), eq("f"), any(AuthenticatedActor.class)))
        .thenThrow(new BeneficiaryException(BeneficiaryException.Kind.FORBIDDEN, "z"));
    when(service.create(any(), eq("u"), any(AuthenticatedActor.class)))
        .thenThrow(new BeneficiaryException(BeneficiaryException.Kind.UNAUTHENTICATED, "w"));
    when(service.create(any(), eq("n"), any(AuthenticatedActor.class)))
        .thenThrow(new BeneficiaryException(BeneficiaryException.Kind.UNKNOWN, "v"));

    mvc.perform(postWithKey("c")).andExpect(status().isConflict());
    mvc.perform(postWithKey("r")).andExpect(status().isUnprocessableEntity());
    mvc.perform(postWithKey("f")).andExpect(status().isForbidden());
    mvc.perform(postWithKey("u")).andExpect(status().isUnauthorized());
    mvc.perform(postWithKey("n")).andExpect(status().isInternalServerError());
  }

  @Test
  void listsOnlyCallerBeneficiaries() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.listForOwner(any(AuthenticatedActor.class)))
        .thenReturn(
            List.of(new BeneficiaryView(id, "Ahorros", destinationId, "DOP", Instant.now())));

    mvc.perform(get("/api/v1/beneficiaries").header("X-Actor-Id", "owner-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].beneficiaryId").value(id.toString()))
        .andExpect(jsonPath("$[0].alias").value("Ahorros"))
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  void getByIdReturns404WhenMissingOrForeign() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.findForOwner(eq(id), any(AuthenticatedActor.class))).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/beneficiaries/" + id).header("X-Actor-Id", "owner-1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$").doesNotExist());
  }

  @Test
  void deleteReturns204And404() throws Exception {
    UUID gone = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    when(service.remove(eq(gone), any(AuthenticatedActor.class))).thenReturn(true);
    when(service.remove(eq(missing), any(AuthenticatedActor.class))).thenReturn(false);

    mvc.perform(delete("/api/v1/beneficiaries/" + gone).header("X-Actor-Id", "owner-1"))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/api/v1/beneficiaries/" + missing).header("X-Actor-Id", "owner-1"))
        .andExpect(status().isNotFound());
  }

  @Test
  void recoveryReturnsOutcomeAnd404() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.findAuthorizedByKey(eq("k1"), eq("hash"), any(AuthenticatedActor.class)))
        .thenReturn(Optional.of(new BeneficiaryOutcome(id, BeneficiaryCreationOutcome.CONFIRMED)));
    when(service.findAuthorizedByKey(eq("no"), eq("hash"), any(AuthenticatedActor.class)))
        .thenReturn(Optional.empty());

    mvc.perform(
            get("/api/v1/beneficiary-operations/k1")
                .header("Idempotency-Key-Hash", "hash")
                .header("X-Actor-Id", "owner-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.beneficiaryId").value(id.toString()))
        .andExpect(jsonPath("$.outcome").value("CONFIRMED"));
    mvc.perform(
            get("/api/v1/beneficiary-operations/no")
                .header("Idempotency-Key-Hash", "hash")
                .header("X-Actor-Id", "owner-1"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/beneficiary-operations/k1").header("X-Actor-Id", "owner-1"))
        .andExpect(status().isBadRequest());
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postWithKey(
      String key) {
    return post("/api/v1/beneficiaries")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .header("Idempotency-Key", key)
        .header("X-Actor-Id", "owner-1");
  }
}
