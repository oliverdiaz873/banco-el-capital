package com.bancoelcapital.financialops;

/** Business or technical deposit failures with explicit outcomes. */
public class DepositOperationException extends RuntimeException {

  public enum Kind {
    UNAUTHENTICATED,
    FORBIDDEN,
    REJECTED,
    CONFLICT,
    FAILED,
    /**
     * Caller-side uncertainty: the request may or may not have taken effect, and nothing was
     * determined. Never a persisted operation state; recovery always reuses the original
     * Idempotency-Key. Maps to HTTP 500 with a retry-with-same-key message.
     */
    UNKNOWN
  }

  private final Kind kind;

  public DepositOperationException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind getKind() {
    return kind;
  }
}
