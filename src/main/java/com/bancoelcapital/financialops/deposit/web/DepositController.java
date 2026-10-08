package com.bancoelcapital.financialops.deposit.web;

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

import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.validation.Valid;

/**
 * API boundary for deposits (ADR-10). Versioned, safe errors, idempotent creation plus queryable
 * outcome for timeout/disconnect recovery. The holder is server-resolved from the account; the
 * request carries no holder identity.
 */
@RestController
@RequestMapping("/api/v1")
public class DepositController {

  private final DepositService service;

  public DepositController(DepositService service) {
    this.service = service;
  }

  @PostMapping("/deposits")
  public ResponseEntity<DepositResponse> create(
      @Valid @RequestBody CreateDepositRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    var result =
        service.deposit(
            new DepositCommand(body.accountId(), body.amountMinorUnits(), body.currency()),
            idempotencyKey,
            new AuthenticatedActor(actorId, Set.of()));
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .body(new DepositResponse(result.operationId(), result.created()));
  }

  @GetMapping("/deposit-operations/{key}")
  public ResponseEntity<DepositOutcomeResponse> findByKey(
      @PathVariable String key,
      @RequestHeader(value = "Idempotency-Key-Hash", required = false) String hash,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    if (hash == null) {
      return ResponseEntity.badRequest().build();
    }
    return service
        .findAuthorizedByKey(key, hash, new AuthenticatedActor(actorId, Set.of()))
        .map(
            found ->
                ResponseEntity.ok(
                    new DepositOutcomeResponse(found.operationId(), found.outcome().name())))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
