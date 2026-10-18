package com.bancoelcapital.financialops.history;

/**
 * Read-only movement history failures with explicit, safe outcomes (ADR-22). No message ever
 * reaches the client verbatim: the web handler maps each kind to a fixed, safe body.
 */
public class MovementHistoryException extends RuntimeException {

  public enum Kind {
    /** Malformed limit or cursor; mapped to 400. */
    INVALID,
    /** Missing or blank actor on an existing account; mapped to 401. */
    UNAUTHENTICATED,
    /**
     * Unknown account or authenticated non-owner, collapsed into one kind so both map to an
     * identical 404 and no account existence leaks.
     */
    NOT_FOUND
  }

  private final Kind kind;

  public MovementHistoryException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public MovementHistoryException(Kind kind) {
    this(kind, kind.name());
  }

  public Kind getKind() {
    return kind;
  }
}
