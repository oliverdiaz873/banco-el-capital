package com.bancoelcapital.financialops.deposit.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bancoelcapital.financialops.deposit.DepositOperationException;
import com.bancoelcapital.financialops.deposit.DepositOperationOutcome;
import com.bancoelcapital.financialops.deposit.DepositOutcome;
import com.bancoelcapital.financialops.deposit.DepositResult;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

@WebMvcTest(DepositController.class)
class DepositControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean DepositService service;

  UUID accountId = UUID.randomUUID();
  String body =
      "{\"accountId\":\"" + accountId + "\",\"amountMinorUnits\":1000,\"currency\":\"DOP\"}";

  @Test
  void returns201OnConfirmation() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.deposit(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new DepositResult(operationId, true));

    mvc.perform(
            post("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.operationId").value(operationId.toString()))
        .andExpect(jsonPath("$.created").value(true));
  }

  @Test
  void returns200OnReplay() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.deposit(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new DepositResult(operationId, false));

    mvc.perform(
            post("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(false));
  }

  @Test
  void mapsConflictRejectedForbiddenUnauthorizedAndUnknown() throws Exception {
    when(service.deposit(any(), eq("c"), any(AuthenticatedActor.class)))
        .thenThrow(new DepositOperationException(DepositOperationException.Kind.CONFLICT, "x"));
    when(service.deposit(any(), eq("r"), any(AuthenticatedActor.class)))
        .thenThrow(new DepositOperationException(DepositOperationException.Kind.REJECTED, "y"));
    when(service.deposit(any(), eq("f"), any(AuthenticatedActor.class)))
        .thenThrow(new DepositOperationException(DepositOperationException.Kind.FORBIDDEN, "z"));
    when(service.deposit(any(), eq("u"), any(AuthenticatedActor.class)))
        .thenThrow(
            new DepositOperationException(DepositOperationException.Kind.UNAUTHENTICATED, "w"));
    when(service.deposit(any(), eq("n"), any(AuthenticatedActor.class)))
        .thenThrow(new DepositOperationException(DepositOperationException.Kind.UNKNOWN, "v"));

    mvc.perform(
            post("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "c")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "r")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            post("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "f")
                .header("X-Actor-Id", "other"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());
    mvc.perform(
            post("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "u"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "n")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isInternalServerError())
        .andExpect(
            jsonPath("$.error").value("uncertain outcome, retry with the same idempotency key"));
  }

  @Test
  void rejectsInvalidRequest() throws Exception {
    mvc.perform(
            post("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\"not-a-uuid\",\"amountMinorUnits\":-5,\"currency\":\"do\"}")
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void queryAuthorizedReturnsOutcome() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenReturn(
            Optional.of(new DepositOutcome(operationId, DepositOperationOutcome.CONFIRMED)));

    mvc.perform(
            get("/api/v1/deposit-operations/k1")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationId").value(operationId.toString()))
        .andExpect(jsonPath("$.outcome").value("CONFIRMED"));
  }

  @Test
  void queryDistinguishesRejected() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenReturn(Optional.of(new DepositOutcome(operationId, DepositOperationOutcome.REJECTED)));

    mvc.perform(
            get("/api/v1/deposit-operations/k1")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("REJECTED"));
  }

  @Test
  void queryUnknownKeyIsNotFound() throws Exception {
    when(service.findAuthorizedByKey(eq("nope"), eq("h"), any(AuthenticatedActor.class)))
        .thenReturn(Optional.empty());

    mvc.perform(
            get("/api/v1/deposit-operations/nope")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isNotFound());
  }

  @Test
  void queryMissingHashIsBadRequest() throws Exception {
    mvc.perform(get("/api/v1/deposit-operations/k1").header("X-Actor-Id", "holder-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void queryUnauthorizedDoesNotLeakOperationId() throws Exception {
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenThrow(new DepositOperationException(DepositOperationException.Kind.FORBIDDEN, "x"));

    mvc.perform(
            get("/api/v1/deposit-operations/k1")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-2"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());
  }
}
