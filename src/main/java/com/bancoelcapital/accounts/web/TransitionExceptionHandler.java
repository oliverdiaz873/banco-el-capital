package com.bancoelcapital.accounts.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.bancoelcapital.accounts.internal.TransitionException;

/**
 * Safe error mapping for lifecycle transitions: no account existence beyond the deterministic
 * rejection, no balances, PII, or internals leaked. FAILED and UNKNOWN keep their technical
 * semantics (never presented as an applied or rejected transition) and map to 500 with same-key
 * recovery messages.
 */
@RestControllerAdvice
public class TransitionExceptionHandler {

  @ExceptionHandler(TransitionException.class)
  public ResponseEntity<Map<String, String>> handle(TransitionException e) {
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
