package com.bancoelcapital.beneficiaries.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.bancoelcapital.beneficiaries.internal.BeneficiaryException;

/**
 * Safe error mapping for beneficiaries: no destination-holder identity, balances, account status,
 * or internals leaked. FAILED and UNKNOWN keep their technical semantics (never presented as a
 * confirmed or rejected beneficiary) and map to 500 with same-key recovery messages; no new HTTP
 * code or persisted state is invented for them.
 */
@RestControllerAdvice
public class BeneficiaryExceptionHandler {

  @ExceptionHandler(BeneficiaryException.class)
  public ResponseEntity<Map<String, String>> handle(BeneficiaryException e) {
    HttpStatus status =
        switch (e.getKind()) {
          case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
          case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
          case CONFLICT -> HttpStatus.CONFLICT;
          case FAILED, UNKNOWN -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    String message =
        switch (e.getKind()) {
          case FAILED ->
              "uncertain outcome, query the result with the idempotency key; retry only with the same key";
          case UNKNOWN -> "uncertain outcome, retry with the same idempotency key";
          default -> e.getMessage();
        };
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
}
