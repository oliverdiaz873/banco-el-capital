package com.bancoelcapital.financialops.transfer.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bancoelcapital.financialops.transfer.TransferOperationException;
import com.bancoelcapital.financialops.transfer.TransferResult;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * P0: XOR destination contract at the HTTP level. Both-present and both-absent are 400 input errors
 * that never reach the service; single-mode requests pass through unchanged.
 */
@WebMvcTest(TransferController.class)
class TransferXorWebMappingTest {

  @Autowired MockMvc mvc;

  @MockitoBean TransferService service;

  @Test
  void bothPresentIsBadRequestWithoutReachingService() throws Exception {
    UUID source = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    UUID beneficiary = UUID.randomUUID();
    String body =
        "{\"sourceAccountId\":\""
            + source
            + "\",\"destinationAccountId\":\""
            + destination
            + "\",\"beneficiaryId\":\""
            + beneficiary
            + "\",\"amountMinorUnits\":1000,\"currency\":\"DOP\"}";

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k-both")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isBadRequest());

    verify(service, never()).transfer(any(), any(), any(AuthenticatedActor.class));
  }

  @Test
  void bothAbsentIsBadRequestWithoutReachingService() throws Exception {
    UUID source = UUID.randomUUID();
    String body =
        "{\"sourceAccountId\":\"" + source + "\",\"amountMinorUnits\":1000,\"currency\":\"DOP\"}";

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k-neither")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isBadRequest());

    verify(service, never()).transfer(any(), any(), any(AuthenticatedActor.class));
  }

  @Test
  void beneficiaryOnlyReachesServiceUnchanged() throws Exception {
    UUID source = UUID.randomUUID();
    UUID beneficiary = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    when(service.transfer(any(), any(), any(AuthenticatedActor.class)))
        .thenReturn(new TransferResult(operationId, true));
    String body =
        "{\"sourceAccountId\":\""
            + source
            + "\",\"beneficiaryId\":\""
            + beneficiary
            + "\",\"amountMinorUnits\":1000,\"currency\":\"DOP\"}";

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k-ben")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.operationId").value(operationId.toString()))
        .andExpect(jsonPath("$.created").value(true));
  }

  @Test
  void invalidBeneficiaryMapsToGenericRejectionWithoutOperationId() throws Exception {
    UUID source = UUID.randomUUID();
    UUID beneficiary = UUID.randomUUID();
    when(service.transfer(any(), any(), any(AuthenticatedActor.class)))
        .thenThrow(
            new TransferOperationException(
                TransferOperationException.Kind.REJECTED, "invalid beneficiary"));
    String body =
        "{\"sourceAccountId\":\""
            + source
            + "\",\"beneficiaryId\":\""
            + beneficiary
            + "\",\"amountMinorUnits\":1000,\"currency\":\"DOP\"}";

    mvc.perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k-invalid-ben")
                .header("X-Actor-Id", "holder-source"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("invalid beneficiary"))
        .andExpect(jsonPath("$.operationId").doesNotExist());
  }
}
