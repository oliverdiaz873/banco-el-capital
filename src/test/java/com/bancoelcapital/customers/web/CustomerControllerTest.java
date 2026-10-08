package com.bancoelcapital.customers.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bancoelcapital.customers.internal.CustomerCreationException;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.customers.internal.CustomerResult;
import com.bancoelcapital.identity.AuthenticatedActor;

@WebMvcTest(CustomerController.class)
class CustomerControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean CustomerCreationService service;

  String body = "{\"customerId\":\"customer-001\",\"displayName\":\"Oliver Diaz\"}";

  @Test
  void returns201OnCreation() throws Exception {
    when(service.create(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new CustomerResult("customer-001", true));

    mvc.perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "customer-001"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.customerId").value("customer-001"));
  }

  @Test
  void returns200OnReplay() throws Exception {
    when(service.create(any(), eq("k1"), any(AuthenticatedActor.class)))
        .thenReturn(new CustomerResult("customer-001", false));

    mvc.perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "customer-001"))
        .andExpect(status().isOk());
  }

  @Test
  void mapsConflictRejectedForbiddenAndUnauthorized() throws Exception {
    when(service.create(any(), eq("c"), any(AuthenticatedActor.class)))
        .thenThrow(new CustomerCreationException(CustomerCreationException.Kind.CONFLICT, "x"));
    when(service.create(any(), eq("r"), any(AuthenticatedActor.class)))
        .thenThrow(new CustomerCreationException(CustomerCreationException.Kind.REJECTED, "y"));
    when(service.create(any(), eq("f"), any(AuthenticatedActor.class)))
        .thenThrow(new CustomerCreationException(CustomerCreationException.Kind.FORBIDDEN, "z"));
    when(service.create(any(), eq("u"), any(AuthenticatedActor.class)))
        .thenThrow(
            new CustomerCreationException(CustomerCreationException.Kind.UNAUTHENTICATED, "w"));

    mvc.perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "c")
                .header("X-Actor-Id", "customer-001"))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "r")
                .header("X-Actor-Id", "customer-001"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "f")
                .header("X-Actor-Id", "other"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.customerId").doesNotExist());
    mvc.perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "u"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsInvalidRequest() throws Exception {
    mvc.perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"bad id!\",\"displayName\":\"x\"}")
                .header("Idempotency-Key", "k1")
                .header("X-Actor-Id", "customer-001"))
        .andExpect(status().isBadRequest());
  }
}
