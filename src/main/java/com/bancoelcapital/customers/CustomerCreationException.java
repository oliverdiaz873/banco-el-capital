package com.bancoelcapital.customers;

/** Business or technical customer-creation failures with explicit outcomes. */
public class CustomerCreationException extends RuntimeException {

  public enum Kind {
    UNAUTHENTICATED,
    FORBIDDEN,
    REJECTED,
    CONFLICT,
    FAILED
  }

  private final Kind kind;

  public CustomerCreationException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind getKind() {
    return kind;
  }
}
