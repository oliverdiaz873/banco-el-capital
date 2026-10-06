package com.bancoelcapital.financialops;

/** Business or technical withdrawal failures with explicit outcomes (ADR-04, ADR-16). */
public class WithdrawalOperationException extends RuntimeException {

  public enum Kind {
    UNAUTHENTICATED,
    FORBIDDEN,
    REJECTED,
    CONFLICT,
    FAILED,
    /**
     * Caller-side uncertainty: the request may or may not have taken effect, and nothing was
     * determined. Never a persisted operation state; recovery always reuses the original
     * Idempotency-Key.
     */
    UNKNOWN
  }

  private final Kind kind;

  public WithdrawalOperationException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind getKind() {
    return kind;
  }
}
