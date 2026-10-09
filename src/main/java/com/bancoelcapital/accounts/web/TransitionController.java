package com.bancoelcapital.accounts.web;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bancoelcapital.accounts.internal.AccountTransition;
import com.bancoelcapital.accounts.internal.AccountTransitionService;
import com.bancoelcapital.accounts.internal.TransitionCommand;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * API boundary for account-lifecycle transitions (ADR-10, ADR-19). Versioned, safe errors,
 * idempotent transitions plus a minimal account read for lifecycle observability. Transitions
 * require BANK_EMPLOYEE; reads require the holder or BANK_EMPLOYEE. A missing Idempotency-Key is an
 * input error (400), never a technical transition failure.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class TransitionController {

  private final AccountTransitionService service;

  public TransitionController(AccountTransitionService service) {
    this.service = service;
  }

  @PostMapping("/{id}/block")
  public ResponseEntity<TransitionResponse> block(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    return transition(id, AccountTransition.BLOCK, idempotencyKey, actorId);
  }

  @PostMapping("/{id}/unblock")
  public ResponseEntity<TransitionResponse> unblock(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    return transition(id, AccountTransition.UNBLOCK, idempotencyKey, actorId);
  }

  @PostMapping("/{id}/close")
  public ResponseEntity<TransitionResponse> close(
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    return transition(id, AccountTransition.CLOSE, idempotencyKey, actorId);
  }

  @GetMapping("/{id}")
  public ResponseEntity<AccountDetailsResponse> findById(
      @PathVariable UUID id,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    return service
        .findAccountView(id, new AuthenticatedActor(actorId, Set.of()))
        .map(
            found ->
                ResponseEntity.ok(
                    new AccountDetailsResponse(
                        found.accountId(),
                        found.holderCustomerId(),
                        found.currency(),
                        found.productCode(),
                        found.status().name(),
                        found.balanceMinorUnits(),
                        found.createdAt())))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private ResponseEntity<TransitionResponse> transition(
      UUID id, AccountTransition transition, String idempotencyKey, String actorId) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    var result =
        service.transition(
            new TransitionCommand(id, transition),
            idempotencyKey,
            new AuthenticatedActor(actorId, Set.of()));
    HttpStatus status = result.changed() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .body(new TransitionResponse(result.accountId(), result.status().name(), result.changed()));
  }
}
