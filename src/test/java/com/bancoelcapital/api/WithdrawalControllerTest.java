package com.bancoelcapital.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.bancoelcapital.financialops.WithdrawalCommand;
import com.bancoelcapital.financialops.WithdrawalOperationException;
import com.bancoelcapital.financialops.WithdrawalOperationOutcome;
import com.bancoelcapital.financialops.WithdrawalOutcome;
import com.bancoelcapital.financialops.WithdrawalResult;
import com.bancoelcapital.financialops.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

@WebMvcTest(WithdrawalController.class)
class WithdrawalControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean WithdrawalService service;

  UUID accountId = UUID.randomUUID();
  String body =
      "{\"accountId\":\"" + accountId + "\",\"amountMinorUnits\":1000,\"currency\":\"DOP\"}";

  @Test
  void returns201OnConfirmation() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.withdraw(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new WithdrawalResult(operationId, true));

    mvc.perform(
            post("/api/v1/withdrawals")
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
    when(service.withdraw(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new WithdrawalResult(operationId, false));

    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(false));
  }

  @Test
  void mapsConflictRejectedForbiddenUnauthorizedAndUnknown() throws Exception {
    when(service.withdraw(any(), eq("c"), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(WithdrawalOperationException.Kind.CONFLICT, "x"));
    when(service.withdraw(any(), eq("r"), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(WithdrawalOperationException.Kind.REJECTED, "y"));
    when(service.withdraw(any(), eq("f"), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(WithdrawalOperationException.Kind.FORBIDDEN, "z"));
    when(service.withdraw(any(), eq("u"), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(
                WithdrawalOperationException.Kind.UNAUTHENTICATED, "w"));
    when(service.withdraw(any(), eq("n"), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(WithdrawalOperationException.Kind.UNKNOWN, "v"));

    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "c")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "r")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "f")
                .header("X-Actor-Id", "other"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());
    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "u"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "n")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isInternalServerError())
        .andExpect(
            jsonPath("$.error").value("uncertain outcome, retry with the same idempotency key"));
  }

  @Test
  void mapsInsufficientFundsTo422WithoutLeakingBalance() throws Exception {
    when(service.withdraw(any(WithdrawalCommand.class), eq("k-nsf"), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(
                WithdrawalOperationException.Kind.REJECTED, "insufficient funds"));

    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k-nsf")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("insufficient funds"))
        .andExpect(jsonPath("$.operationId").doesNotExist());
  }

  @Test
  void rejectsInvalidRequest() throws Exception {
    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\"not-a-uuid\",\"amountMinorUnits\":-5,\"currency\":\"do\"}")
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isBadRequest());

    verify(service, never()).withdraw(any(), any(), any());
  }

  @Test
  void missingIdempotencyKeyMapsToServiceFailure() throws Exception {
    when(service.withdraw(any(), isNull(), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(
                WithdrawalOperationException.Kind.FAILED, "idempotency key is required"));

    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void missingActorMapsToUnauthorized() throws Exception {
    when(service.withdraw(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(
                WithdrawalOperationException.Kind.UNAUTHENTICATED, "unauthenticated actor"));

    mvc.perform(
            post("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void queryAuthorizedReturnsOutcome() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenReturn(
            Optional.of(new WithdrawalOutcome(operationId, WithdrawalOperationOutcome.CONFIRMED)));

    mvc.perform(
            get("/api/v1/withdrawal-operations/k1")
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
        .thenReturn(
            Optional.of(new WithdrawalOutcome(operationId, WithdrawalOperationOutcome.REJECTED)));

    mvc.perform(
            get("/api/v1/withdrawal-operations/k1")
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
            get("/api/v1/withdrawal-operations/nope")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isNotFound());
  }

  @Test
  void queryMissingHashIsBadRequest() throws Exception {
    mvc.perform(get("/api/v1/withdrawal-operations/k1").header("X-Actor-Id", "holder-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void queryUnauthorizedDoesNotLeakOperationId() throws Exception {
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(WithdrawalOperationException.Kind.FORBIDDEN, "x"));

    mvc.perform(
            get("/api/v1/withdrawal-operations/k1")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-2"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());
  }

  @Test
  void queryUnauthenticatedIsUnauthorized() throws Exception {
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenThrow(
            new WithdrawalOperationException(
                WithdrawalOperationException.Kind.UNAUTHENTICATED, "unauthenticated actor"));

    mvc.perform(get("/api/v1/withdrawal-operations/k1").header("Idempotency-Key-Hash", "h"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.operationId").doesNotExist());
  }
}
