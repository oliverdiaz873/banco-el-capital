package com.bancoelcapital.beneficiaries.internal;

/** Business or technical beneficiary failures with explicit outcomes. */
public class BeneficiaryException extends RuntimeException {

  public enum Kind {
    UNAUTHENTICATED,
    FORBIDDEN,
    REJECTED,
    CONFLICT,
    FAILED,
    /**
     * Caller-side uncertainty: the request may or may not have taken effect, and nothing was
     * determined. Never a persisted creation state; recovery always reuses the original
     * Idempotency-Key. Maps to HTTP 500 with a retry-with-same-key message.
     */
    UNKNOWN
  }

  private final Kind kind;

  public BeneficiaryException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind getKind() {
    return kind;
  }
}
