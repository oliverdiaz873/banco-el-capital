package com.bancoelcapital.beneficiaries.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bancoelcapital.beneficiaries.internal.BeneficiaryCommand;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryService;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryView;
import com.bancoelcapital.identity.AuthenticatedActor;

import jakarta.validation.Valid;

/**
 * API boundary for beneficiaries (ADR-10, ADR-20). Versioned, safe errors, idempotent creation plus
 * queryable outcome for timeout/disconnect recovery. The owner is server-resolved from the actor;
 * the request carries no owner identity. A missing Idempotency-Key is an input error (400), never a
 * technical operation failure. Missing rows and foreign rows share one 404.
 */
@RestController
@RequestMapping("/api/v1")
public class BeneficiaryController {

  private final BeneficiaryService service;

  public BeneficiaryController(BeneficiaryService service) {
    this.service = service;
  }

  @PostMapping("/beneficiaries")
  public ResponseEntity<BeneficiaryResponse> create(
      @Valid @RequestBody CreateBeneficiaryRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    var result =
        service.create(
            new BeneficiaryCommand(body.destinationAccountId(), body.alias()),
            idempotencyKey,
            new AuthenticatedActor(actorId, Set.of()));
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .body(new BeneficiaryResponse(result.beneficiaryId(), result.created()));
  }

  @GetMapping("/beneficiaries")
  public ResponseEntity<List<BeneficiaryDetailsResponse>> list(
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    List<BeneficiaryDetailsResponse> response =
        service.listForOwner(new AuthenticatedActor(actorId, Set.of())).stream()
            .map(BeneficiaryController::detailsOf)
            .toList();
    return ResponseEntity.ok(response);
  }

  @GetMapping("/beneficiaries/{id}")
  public ResponseEntity<BeneficiaryDetailsResponse> findById(
      @PathVariable UUID id,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    return service
        .findForOwner(id, new AuthenticatedActor(actorId, Set.of()))
        .map(found -> ResponseEntity.ok(detailsOf(found)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @DeleteMapping("/beneficiaries/{id}")
  public ResponseEntity<Void> remove(
      @PathVariable UUID id,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    boolean removed = service.remove(id, new AuthenticatedActor(actorId, Set.of()));
    if (!removed) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/beneficiary-operations/{key}")
  public ResponseEntity<BeneficiaryOutcomeResponse> findByKey(
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
                    new BeneficiaryOutcomeResponse(found.beneficiaryId(), found.outcome().name())))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static BeneficiaryDetailsResponse detailsOf(BeneficiaryView view) {
    return new BeneficiaryDetailsResponse(
        view.beneficiaryId(),
        view.alias(),
        view.destinationAccountId(),
        view.destinationCurrency(),
        view.createdAt());
  }
}
