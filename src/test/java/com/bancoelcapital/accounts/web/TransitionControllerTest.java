package com.bancoelcapital.accounts.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bancoelcapital.accounts.internal.AccountStatus;
import com.bancoelcapital.accounts.internal.AccountTransitionService;
import com.bancoelcapital.accounts.internal.AccountView;
import com.bancoelcapital.accounts.internal.TransitionException;
import com.bancoelcapital.accounts.internal.TransitionResult;
import com.bancoelcapital.identity.AuthenticatedActor;

@WebMvcTest(TransitionController.class)
class TransitionControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean AccountTransitionService service;

  UUID accountId = UUID.randomUUID();

  @Test
  void returns201OnAppliedTransition() throws Exception {
    when(service.transition(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new TransitionResult(accountId, AccountStatus.BLOCKED, true));

    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/block")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.status").value("BLOCKED"))
        .andExpect(jsonPath("$.changed").value(true));
  }

  @Test
  void returns200OnNoOpReplay() throws Exception {
    when(service.transition(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new TransitionResult(accountId, AccountStatus.BLOCKED, false));

    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/block")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));
  }

  @Test
  void mapsConflictRejectedForbiddenUnauthorizedAndUnknown() throws Exception {
    when(service.transition(any(), eq("c"), any(AuthenticatedActor.class)))
        .thenThrow(new TransitionException(TransitionException.Kind.CONFLICT, "x"));
    when(service.transition(any(), eq("r"), any(AuthenticatedActor.class)))
        .thenThrow(new TransitionException(TransitionException.Kind.REJECTED, "y"));
    when(service.transition(any(), eq("f"), any(AuthenticatedActor.class)))
        .thenThrow(new TransitionException(TransitionException.Kind.FORBIDDEN, "z"));
    when(service.transition(any(), eq("u"), any(AuthenticatedActor.class)))
        .thenThrow(new TransitionException(TransitionException.Kind.UNAUTHENTICATED, "w"));
    when(service.transition(any(), eq("n"), any(AuthenticatedActor.class)))
        .thenThrow(new TransitionException(TransitionException.Kind.UNKNOWN, "v"));
    when(service.transition(any(), eq("t"), any(AuthenticatedActor.class)))
        .thenThrow(new TransitionException(TransitionException.Kind.FAILED, "t"));

    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "c")
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "r")
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "f")
                .header("X-Actor-Id", "other"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.accountId").doesNotExist());
    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "u"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "n")
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isInternalServerError())
        .andExpect(
            jsonPath("$.error").value("uncertain outcome, retry with the same idempotency key"));
    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "t")
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isInternalServerError())
        .andExpect(
            jsonPath("$.error")
                .value(
                    "uncertain outcome, query the result with the idempotency key; retry only with the same key"));
  }

  @Test
  void missingIdempotencyKeyIsBadRequest() throws Exception {
    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/block")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/block")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "  ")
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidAccountIdIsBadRequest() throws Exception {
    mvc.perform(
            post("/api/v1/accounts/not-a-uuid/block")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void illegalTransitionMapsToUnprocessableEntityWithoutLeak() throws Exception {
    when(service.transition(any(), eq("closed"), any(AuthenticatedActor.class)))
        .thenThrow(new TransitionException(TransitionException.Kind.REJECTED, "terminal state"));

    mvc.perform(
            post("/api/v1/accounts/" + accountId + "/block")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "closed")
                .header("X-Actor-Id", "emp-1"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("terminal state"))
        .andExpect(jsonPath("$.balanceMinorUnits").doesNotExist());
  }

  @Test
  void queryAuthorizedReturnsAccountDetails() throws Exception {
    when(service.findAccountView(eq(accountId), any(AuthenticatedActor.class)))
        .thenReturn(
            Optional.of(
                new AccountView(
                    accountId,
                    "holder-1",
                    "DOP",
                    "BASIC",
                    AccountStatus.BLOCKED,
                    0L,
                    Instant.parse("2026-01-01T00:00:00Z"))));

    mvc.perform(get("/api/v1/accounts/" + accountId).header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.status").value("BLOCKED"))
        .andExpect(jsonPath("$.balanceMinorUnits").value(0));
  }

  @Test
  void queryUnknownAccountIsNotFound() throws Exception {
    when(service.findAccountView(eq(accountId), any(AuthenticatedActor.class)))
        .thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/accounts/" + accountId).header("X-Actor-Id", "holder-1"))
        .andExpect(status().isNotFound());
  }

  @Test
  void queryUnauthorizedDoesNotLeakAccount() throws Exception {
    when(service.findAccountView(eq(accountId), any(AuthenticatedActor.class)))
        .thenThrow(new TransitionException(TransitionException.Kind.FORBIDDEN, "x"));

    mvc.perform(get("/api/v1/accounts/" + accountId).header("X-Actor-Id", "holder-2"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.accountId").doesNotExist())
        .andExpect(jsonPath("$.holderCustomerId").doesNotExist());
  }
}
