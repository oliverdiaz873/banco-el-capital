package com.bancoelcapital.api;

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

import com.bancoelcapital.accounts.AccountCreationException;
import com.bancoelcapital.accounts.AccountCreationService;
import com.bancoelcapital.accounts.AccountResult;
import com.bancoelcapital.identity.AuthenticatedActor;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean AccountCreationService service;

  String body =
      "{\"holderCustomerId\":\"holder-1\",\"currency\":\"DOP\",\"productCode\":\"BASIC\"}";

  @Test
  void returns201OnCreation() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.create(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new AccountResult(id, true));

    mvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accountId").value(id.toString()));
  }

  @Test
  void returns200OnReplay() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.create(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new AccountResult(id, false));

    mvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk());
  }

  @Test
  void mapsConflictRejectedAndForbidden() throws Exception {
    when(service.create(any(), eq("c"), any(AuthenticatedActor.class)))
        .thenThrow(new AccountCreationException(AccountCreationException.Kind.CONFLICT, "x"));
    when(service.create(any(), eq("r"), any(AuthenticatedActor.class)))
        .thenThrow(new AccountCreationException(AccountCreationException.Kind.REJECTED, "y"));
    when(service.create(any(), eq("f"), any(AuthenticatedActor.class)))
        .thenThrow(new AccountCreationException(AccountCreationException.Kind.FORBIDDEN, "z"));

    mvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "c")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "r")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "f")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isForbidden());
  }

  @Test
  void queryAuthorizedReturnsAccount() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenReturn(Optional.of(new AccountResult(id, false)));

    mvc.perform(
            get("/api/v1/account-creations/k1")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(id.toString()));
  }

  @Test
  void queryUnauthorizedDoesNotLeakAccountId() throws Exception {
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenThrow(new AccountCreationException(AccountCreationException.Kind.FORBIDDEN, "x"));

    mvc.perform(
            get("/api/v1/account-creations/k1")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-2"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.accountId").doesNotExist());
  }

  @Test
  void queryMissingActorIsUnauthorized() throws Exception {
    when(service.findAuthorizedByKey(eq("k1"), eq("h"), any(AuthenticatedActor.class)))
        .thenThrow(
            new AccountCreationException(AccountCreationException.Kind.UNAUTHENTICATED, "y"));

    mvc.perform(get("/api/v1/account-creations/k1").header("Idempotency-Key-Hash", "h"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void queryUnknownKeyIsNotFound() throws Exception {
    when(service.findAuthorizedByKey(eq("nope"), eq("h"), any(AuthenticatedActor.class)))
        .thenReturn(Optional.empty());

    mvc.perform(
            get("/api/v1/account-creations/nope")
                .header("Idempotency-Key-Hash", "h")
                .header("X-Actor-Id", "holder-1"))
        .andExpect(status().isNotFound());
  }
}
