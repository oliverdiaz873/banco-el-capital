package com.bancoelcapital.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.bancoelcapital.financialops.WithdrawalOperationException;

/**
 * Safe error mapping for withdrawals: no account existence, balances, PII, or internals leaked.
 * UNKNOWN is caller-side uncertainty (never a persisted state) and maps to 500 with a same-key
 * recovery message; no new HTTP code or persisted state is invented for it.
 */
@RestControllerAdvice
public class WithdrawalExceptionHandler {

  @ExceptionHandler(WithdrawalOperationException.class)
  public ResponseEntity<Map<String, String>> handle(WithdrawalOperationException e) {
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
          case FAILED -> "uncertain outcome, query the idempotency key";
          case UNKNOWN -> "uncertain outcome, retry with the same idempotency key";
          default -> e.getMessage();
        };
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
}
