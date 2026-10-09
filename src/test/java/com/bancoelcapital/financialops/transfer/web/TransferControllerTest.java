package com.bancoelcapital.financialops.transfer.web;

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

import com.bancoelcapital.financialops.transfer.TransferOperationException;
import com.bancoelcapital.financialops.transfer.TransferOperationOutcome;
import com.bancoelcapital.financialops.transfer.TransferOutcome;
import com.bancoelcapital.financialops.transfer.TransferResult;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.identity.AuthenticatedActor;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean TransferService service;

  UUID sourceId = UUID.randomUUID();
  UUID destinationId = UUID.randomUUID();
  String body =
      "{\"sourceAccountId\":\""
          + sourceId
          + "\",\"destinationAccountId\":\""
          + destinationId
          + "\",\"amountMinorUnits\":1000,\"currency\":\"DOP\"}";

  @Test
  void returns201OnConfirmation() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.transfer(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new TransferResult(operationId, true));

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.operationId").value(operationId.toString()))
        .andExpect(jsonPath("$.created").value(true));
  }

  @Test
  void returns200OnReplay() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.transfer(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new TransferResult(operationId, false));

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(false));
  }

  @Test
  void mapsConflictRejectedForbiddenUnauthorizedAndUnknown() throws Exception {
    when(service.transfer(any(), eq("c"), any(AuthenticatedActor.class)))
        .thenThrow(new TransferOperationException(TransferOperationException.Kind.CONFLICT, "x"));
    when(service.transfer(any(), eq("r"), any(AuthenticatedActor.class)))
        .thenThrow(new TransferOperationException(TransferOperationException.Kind.REJECTED, "y"));
    when(service.transfer(any(), eq("f"), any(AuthenticatedActor.class)))
        .thenThrow(new TransferOperationException(TransferOperationException.Kind.FORBIDDEN, "z"));
    when(service.transfer(any(), eq("u"), any(AuthenticatedActor.class)))
        .thenThrow(
            new TransferOperationException(TransferOperationException.Kind.UNAUTHENTICATED, "w"));
    when(service.transfer(any(), eq("n"), any(AuthenticatedActor.class)))
        .thenThrow(new TransferOperationException(TransferOperationException.Kind.UNKNOWN, "v"));
    when(service.transfer(any(), eq("t"), any(AuthenticatedActor.class)))
        .thenThrow(new TransferOperationException(TransferOperationException.Kind.FAILED, "t"));

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "c")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "r")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "f")
                .header("X-Actor-Id", "other"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());
    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "u"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "n")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isInternalServerError())
        .andExpect(
            jsonPath("$.error").value("uncertain outcome, retry with the same idempotency key"));
    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "t")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isInternalServerError())
        .andExpect(
            jsonPath("$.error")
                .value(
                    "uncertain outcome, query the result with the idempotency key; retry only with the same key"));
  }

  @Test
  void rejectsInvalidRequest() throws Exception {
    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceAccountId\":\"not-a-uuid\",\"destinationAccountId\":\""
                        + destinationId
                        + "\",\"amountMinorUnits\":-5,\"currency\":\"do\"}")
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void missingIdempotencyKeyIsBadRequest() throws Exception {
    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "  ")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void insufficientFundsMapsToUnprocessableEntityWithoutBalanceLeak() throws Exception {
    when(service.transfer(any(), eq("funds"), any(AuthenticatedActor.class)))
        .thenThrow(
            new TransferOperationException(
                TransferOperationException.Kind.REJECTED, "insufficient funds"));

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "funds")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("insufficient funds"))
        .andExpect(jsonPath("$.balance").doesNotExist())
        .andExpect(jsonPath("$.balanceMinorUnits").doesNotExist());
  }

  @Test
  void queryAuthorizedReturnsOutcome() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenReturn(
            Optional.of(new TransferOutcome(operationId, TransferOperationOutcome.CONFIRMED)));

    mvc.perform(
            get("/api/v1/transfer-operations/k1")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationId").value(operationId.toString()))
        .andExpect(jsonPath("$.outcome").value("CONFIRMED"));
  }

  @Test
  void queryDistinguishesRejected() throws Exception {
    UUID operationId = UUID.randomUUID();
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenReturn(
            Optional.of(new TransferOutcome(operationId, TransferOperationOutcome.REJECTED)));

    mvc.perform(
            get("/api/v1/transfer-operations/k1")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("REJECTED"));
  }

  @Test
  void queryUnknownKeyIsNotFound() throws Exception {
    when(service.findAuthorizedByKey(eq("nope"), eq("h"), any(AuthenticatedActor.class)))
        .thenReturn(Optional.empty());

    mvc.perform(
            get("/api/v1/transfer-operations/nope")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isNotFound());
  }

  @Test
  void queryMissingHashIsBadRequest() throws Exception {
    mvc.perform(get("/api/v1/transfer-operations/k1").header("X-Actor-Id", "holder-source"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void queryUnauthorizedDoesNotLeakOperationId() throws Exception {
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenThrow(new TransferOperationException(TransferOperationException.Kind.FORBIDDEN, "x"));

    mvc.perform(
            get("/api/v1/transfer-operations/k1")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-dest"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.operationId").doesNotExist());
  }
}
