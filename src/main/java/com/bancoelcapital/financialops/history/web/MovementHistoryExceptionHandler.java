package com.bancoelcapital.financialops.history.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.bancoelcapital.financialops.history.MovementHistoryException;

/**
 * Safe error mapping for the read-only movement history (ADR-22). The 404 body is a single constant
 * used for BOTH the unknown account and the authenticated non-owner, so the two responses are
 * byte-for-byte identical and neither leaks the other. No account existence, balance, PII, internal
 * operation state or exception detail ever reaches the client.
 */
@RestControllerAdvice
public class MovementHistoryExceptionHandler {

  private static final Map<String, String> INVALID_BODY = Map.of("error", "invalid request");
  private static final Map<String, String> UNAUTHENTICATED_BODY = Map.of("error", "unauthorized");
  private static final Map<String, String> NOT_FOUND_BODY = Map.of("error", "not found");

  @ExceptionHandler(MovementHistoryException.class)
  public ResponseEntity<Map<String, String>> handle(MovementHistoryException e) {
    HttpStatus status =
        switch (e.getKind()) {
          case INVALID -> HttpStatus.BAD_REQUEST;
          case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
    Map<String, String> body =
        switch (e.getKind()) {
          case INVALID -> INVALID_BODY;
          case UNAUTHENTICATED -> UNAUTHENTICATED_BODY;
          case NOT_FOUND -> NOT_FOUND_BODY;
        };
    return ResponseEntity.status(status).body(body);
  }
}
