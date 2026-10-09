package com.bancoelcapital.accounts.internal;

/**
 * Pure lifecycle-transition rules (ADR-19). Decides, from the current status and the requested
 * transition alone, whether the state changes, converges without effect, or refuses. No I/O, no
 * transactions, no balance reads: the zero-balance precondition for {@code CLOSE} and all
 * persistence live in the service, evaluated under the row write lock after this matrix.
 */
public final class TransitionRules {

  private TransitionRules() {}

  /** Outcome of evaluating one transition against one current status. */
  public sealed interface Decision permits Decision.Confirm, Decision.NoOp, Decision.Reject {

    /** The transition applies: move to the given status. */
    record Confirm(AccountStatus status) implements Decision {}

    /** Already in the converged state: success without any effect. */
    record NoOp(AccountStatus status) implements Decision {}

    /** The transition is illegal from the current status. */
    record Reject(String reason) implements Decision {}
  }

  public static Decision evaluate(AccountStatus current, AccountTransition requested) {
    return switch (requested) {
      case BLOCK ->
          switch (current) {
            case ACTIVE -> new Decision.Confirm(AccountStatus.BLOCKED);
            case BLOCKED -> new Decision.NoOp(AccountStatus.BLOCKED);
            case CLOSED -> new Decision.Reject("closed accounts cannot be blocked");
          };
      case UNBLOCK ->
          switch (current) {
            case ACTIVE -> new Decision.NoOp(AccountStatus.ACTIVE);
            case BLOCKED -> new Decision.Confirm(AccountStatus.ACTIVE);
            case CLOSED -> new Decision.Reject("closed accounts cannot be unblocked");
          };
      case CLOSE ->
          switch (current) {
            case ACTIVE, BLOCKED -> new Decision.Confirm(AccountStatus.CLOSED);
            case CLOSED -> new Decision.Reject("account is already closed");
          };
    };
  }
}
