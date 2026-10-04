package com.bancoelcapital.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.bancoelcapital.accounts.AccountCreationException;

/** Safe error mapping: no account existence, balances, PII or internals leaked. */
@RestControllerAdvice
public class AccountExceptionHandler {

  @ExceptionHandler(AccountCreationException.class)
  public ResponseEntity<Map<String, String>> handle(AccountCreationException e) {
    HttpStatus status =
        switch (e.getKind()) {
          case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
          case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
          case CONFLICT -> HttpStatus.CONFLICT;
          case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    String message =
        e.getKind() == AccountCreationException.Kind.FAILED
            ? "uncertain outcome, query the idempotency key"
            : e.getMessage();
    return ResponseEntity.status(status).body(Map.of("error", message));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> handleInvalid(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest().body(Map.of("error", "invalid request"));
  }
}
