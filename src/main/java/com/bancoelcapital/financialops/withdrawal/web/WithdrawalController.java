package com.bancoelcapital.financialops.withdrawal.web;

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

import com.bancoelcapital.financialops.withdrawal.WithdrawalCommand;
import com.bancoelcapital.financialops.withdrawal.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.validation.Valid;

/**
 * API boundary for withdrawals (ADR-10). Versioned, safe errors, idempotent creation plus queryable
 * outcome for timeout/disconnect recovery. The holder is server-resolved from the account; the
 * request carries no holder identity. No financial logic lives here: the controller only adapts
 * HTTP to the withdrawal use case and back.
 */
@RestController
@RequestMapping("/api/v1")
public class WithdrawalController {

  private final WithdrawalService service;

  public WithdrawalController(WithdrawalService service) {
    this.service = service;
  }

  @PostMapping("/withdrawals")
  public ResponseEntity<WithdrawalResponse> create(
      @Valid @RequestBody CreateWithdrawalRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    var result =
        service.withdraw(
            new WithdrawalCommand(body.accountId(), body.amountMinorUnits(), body.currency()),
            idempotencyKey,
            new AuthenticatedActor(actorId, Set.of()));
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .body(new WithdrawalResponse(result.operationId(), result.created()));
  }

  @GetMapping("/withdrawal-operations/{key}")
  public ResponseEntity<WithdrawalOutcomeResponse> findByKey(
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
                    new WithdrawalOutcomeResponse(found.operationId(), found.outcome().name())))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
