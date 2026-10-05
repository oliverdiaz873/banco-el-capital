package com.bancoelcapital.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.bancoelcapital.customers.CustomerCreationException;

/** Safe error mapping for customer creation: no PII or internals leaked. */
@RestControllerAdvice
public class CustomerExceptionHandler {

  @ExceptionHandler(CustomerCreationException.class)
  public ResponseEntity<Map<String, String>> handle(CustomerCreationException e) {
    HttpStatus status =
        switch (e.getKind()) {
          case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
          case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
          case CONFLICT -> HttpStatus.CONFLICT;
          case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    String message =
        e.getKind() == CustomerCreationException.Kind.FAILED
            ? "uncertain outcome, query the idempotency key"
            : e.getMessage();
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
}
