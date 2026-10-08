package com.bancoelcapital.accounts.internal;

/** Business or technical creation failures with explicit outcomes. */
public class AccountCreationException extends RuntimeException {

  public enum Kind {
    UNAUTHENTICATED,
    FORBIDDEN,
    REJECTED,
    CONFLICT,
    FAILED
  }

  private final Kind kind;

  public AccountCreationException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind getKind() {
    return kind;
  }
}
