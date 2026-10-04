package com.bancoelcapital.api;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bancoelcapital.accounts.AccountCreationCommand;
import com.bancoelcapital.accounts.AccountCreationService;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.validation.Valid;

/**
 * API boundary for account creation (ADR-10). Versioned, safe errors, idempotent creation plus
 * queryable outcome for timeout/disconnect recovery.
 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {

  private final AccountCreationService service;

  public AccountController(AccountCreationService service) {
    this.service = service;
  }

  @PostMapping("/accounts")
  public ResponseEntity<AccountResponse> create(
      @Valid @RequestBody CreateAccountRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    var result =
        service.create(
            new AccountCreationCommand(
                body.holderCustomerId(), body.currency(), body.productCode()),
            idempotencyKey,
            new AuthenticatedActor(actorId, Set.of()));
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .body(new AccountResponse(result.accountId(), result.created()));
  }

  @GetMapping("/account-creations/{key}")
  public ResponseEntity<AccountResponse> findByKey(
      @PathVariable String key,
      @RequestHeader(value = "Idempotency-Key-Hash", required = false) String hash,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    if (hash == null) {
      return ResponseEntity.badRequest().build();
    }
    return service
        .findAuthorizedByKey(key, hash, new AuthenticatedActor(actorId, Set.of()))
        .map(found -> ResponseEntity.ok(new AccountResponse(found.accountId(), false)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
