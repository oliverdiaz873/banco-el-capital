package com.bancoelcapital.financialops.transfer.web;

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

import com.bancoelcapital.financialops.transfer.TransferCommand;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.validation.Valid;

/**
 * API boundary for transfers (ADR-10, ADR-18). Versioned, safe errors, idempotent creation plus
 * queryable outcome for timeout/disconnect recovery. Both holders are server-resolved from the
 * accounts and authorization applies to the source holder only; the request carries no holder
 * identity. A missing Idempotency-Key is an input error (400), never a technical operation failure.
 */
@RestController
@RequestMapping("/api/v1")
public class TransferController {

  private final TransferService service;

  public TransferController(TransferService service) {
    this.service = service;
  }

  @PostMapping("/transfers")
  public ResponseEntity<TransferResponse> create(
      @Valid @RequestBody CreateTransferRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    var result =
        service.transfer(
            new TransferCommand(
                body.sourceAccountId(),
                body.destinationAccountId(),
                body.amountMinorUnits(),
                body.currency()),
            idempotencyKey,
            new AuthenticatedActor(actorId, Set.of()));
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .body(new TransferResponse(result.operationId(), result.created()));
  }

  @GetMapping("/transfer-operations/{key}")
  public ResponseEntity<TransferOutcomeResponse> findByKey(
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
                    new TransferOutcomeResponse(found.operationId(), found.outcome().name())))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
