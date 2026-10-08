package com.bancoelcapital.customers.web;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.validation.Valid;

/**
 * API boundary for customer creation (ADR-10). Versioned, safe errors, idempotent creation only. No
 * public GET in MVP: existence is an internal Accounts -&gt; Identity contract.
 */
@RestController
@RequestMapping("/api/v1")
public class CustomerController {

  private final CustomerCreationService service;

  public CustomerController(CustomerCreationService service) {
    this.service = service;
  }

  @PostMapping("/customers")
  public ResponseEntity<CustomerResponse> create(
      @Valid @RequestBody CreateCustomerRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    var result =
        service.create(
            new CustomerCreationCommand(body.customerId(), body.displayName()),
            idempotencyKey,
            new AuthenticatedActor(actorId, Set.of()));
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .body(new CustomerResponse(result.customerId(), result.created()));
  }
}
