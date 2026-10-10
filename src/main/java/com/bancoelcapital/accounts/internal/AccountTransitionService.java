package com.bancoelcapital.accounts.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bancoelcapital.audit.api.AuditEntry;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * Account-lifecycle transition use case (Accounts module, ADR-03/ADR-19). Coordinates validation
 * through TransitionRules, idempotency in a dedicated namespace, and the state effect through the
 * account-row write lock. Each confirmation runs in exactly one local transaction; the winner
 * reload after a uniqueness race runs in a separate fresh read transaction (see below).
 *
 * <p>Transitions require the BANK_EMPLOYEE role (P3 decided): the actor is authorized against the
 * server-resolved holder before any replay or attempt, so unauthorized callers learn nothing from
 * idempotency behavior.
 *
 * <p>PostgreSQL aborts the whole transaction on any constraint violation, so a reload issued inside
 * the same transaction after a DataIntegrityViolationException could never succeed there. The write
 * attempt therefore runs in its own transaction, which the template rolls back on violation, and
 * the winner lookup runs afterwards in a brand-new read transaction against committed state. This
 * relies only on standard local-transaction semantics, identical on H2 and PostgreSQL.
 *
 * <p>UNKNOWN is caller-side uncertainty, never a persisted state: a client that loses the response
 * retries with the same Idempotency-Key and deterministically receives the stored result. FAILED is
 * thrown only for determined technical failures with no state effect.
 */
@Service
public class AccountTransitionService {

  private final AccountRepository accounts;
  private final TransitionIdempotencyRepository idempotency;
  private final AuditRecorder audit;
  private final AuthorizationService authorization;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  public AccountTransitionService(
      AccountRepository accounts,
      TransitionIdempotencyRepository idempotency,
      AuditRecorder audit,
      AuthorizationService authorization,
      PlatformTransactionManager transactionManager) {
    this.accounts = accounts;
    this.idempotency = idempotency;
    this.audit = audit;
    this.authorization = authorization;
    this.writeTx = new TransactionTemplate(transactionManager);
    this.readTx = new TransactionTemplate(transactionManager);
    this.readTx.setReadOnly(true);
  }

  public TransitionResult transition(
      TransitionCommand command, String idempotencyKey, AuthenticatedActor actor) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new TransitionException(TransitionException.Kind.FAILED, "idempotency key is required");
    }
    // Holder is server-resolved without a lock for the authorization gate; the locked row
    // inside the attempt is the source of truth for rules (holders are immutable, so the
    // two reads cannot disagree).
    Optional<String> holder =
        accounts.findById(command.accountId()).map(Account::getHolderCustomerId);
    if (holder.isEmpty()) {
      String hash = requestHash(command);
      return writeTx
          .execute(status -> reject(command, idempotencyKey, hash, actor, "unknown account"))
          .resultOrThrow();
    }
    var decision = authorization.decideTransition(actor, holder.get());
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    String hash = requestHash(command);
    Optional<TransitionIdempotency> existing = idempotency.findById(idempotencyKey);
    if (existing.isPresent()) {
      if (!existing.get().getRequestHash().equals(hash)) {
        return conflict(idempotencyKey, actor);
      }
      return replay(existing.get());
    }
    final AttemptOutcome outcome;
    try {
      outcome = writeTx.execute(status -> attempt(command, idempotencyKey, hash, actor));
    } catch (DuplicateDetected | DataIntegrityViolationException race) {
      // Our own write transaction rolled back with nothing persisted: either a concurrent
      // same-key attempt committed while we waited on the account row lock (detected by the
      // post-lock re-check), or the database arbiter rejected our insert. Reload the winner
      // in a fresh read transaction; what we read there is committed state on every engine.
      Optional<TransitionIdempotency> winner =
          readTx.execute(status -> idempotency.findById(idempotencyKey));
      if (winner.isEmpty()) {
        // Nothing determined: the winner may still be in flight. The caller is uncertain,
        // not failed; recovery reuses the same key.
        throw new TransitionException(
            TransitionException.Kind.UNKNOWN,
            "uncertain outcome, retry with the same idempotency key");
      }
      if (!winner.get().getRequestHash().equals(hash)) {
        return conflict(idempotencyKey, actor);
      }
      return replay(winner.get());
    }
    return outcome.resultOrThrow();
  }

  @Transactional(readOnly = true)
  public Optional<TransitionOutcome> outcomeOf(String idempotencyKey) {
    return idempotency.findById(idempotencyKey).map(TransitionIdempotency::getOutcome);
  }

  /**
   * Minimal account read for lifecycle observability. Never re-executes a transition and never
   * mutates state. The caller MUST be the holder or carry BANK_EMPLOYEE; absence and denial resolve
   * without leaking which accounts exist to strangers.
   */
  @Transactional(readOnly = true)
  public Optional<AccountView> findAccountView(UUID accountId, AuthenticatedActor actor) {
    Optional<Account> stored = accounts.findById(accountId);
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    var decision = authorization.decideViewAccount(actor, stored.get().getHolderCustomerId());
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    Account account = stored.get();
    return Optional.of(
        new AccountView(
            account.getId(),
            account.getHolderCustomerId(),
            account.getCurrency(),
            account.getProductCode(),
            account.getStatus(),
            account.getBalanceMinorUnits(),
            account.getCreatedAt()));
  }

  /**
   * Runs inside exactly one local write transaction. The row lock is acquired first; the definitive
   * evaluation (rules plus the zero-balance precondition for CLOSE) happens on the locked row.
   * Returns normally (never throws business outcomes) so REJECTED records actually commit;
   * technical failures propagate and roll back.
   */
  private AttemptOutcome attempt(
      TransitionCommand command, String idempotencyKey, String hash, AuthenticatedActor actor) {
    Optional<Account> stored = accounts.findByIdForUpdate(command.accountId());
    // Post-lock re-check: the lock serialized us, so a concurrent same-key attempt may have
    // committed while we waited. Its idempotency row is committed state now and visible to
    // this fresh read on every engine; bailing here keeps us from writing a second effect
    // that a later uniqueness check might miss.
    if (idempotency.findById(idempotencyKey).isPresent()) {
      throw new DuplicateDetected();
    }
    if (stored.isEmpty()) {
      return reject(command, idempotencyKey, hash, actor, "unknown account");
    }
    Account account = stored.get();
    AccountStatus previous = account.getStatus();
    TransitionRules.Decision decision = TransitionRules.evaluate(previous, command.transition());
    if (decision instanceof TransitionRules.Decision.Reject rejected) {
      return reject(command, idempotencyKey, hash, actor, rejected.reason());
    }
    if (decision instanceof TransitionRules.Decision.NoOp noOp) {
      // State convergence: success without any state change and without new audit. The
      // idempotency record still commits so the same key replays deterministically.
      idempotency.save(
          new TransitionIdempotency(
              idempotencyKey, command.accountId(), hash, TransitionOutcome.CONFIRMED));
      return new AttemptOutcome.Confirmed(
          new TransitionResult(command.accountId(), noOp.status(), false));
    }
    TransitionRules.Decision.Confirm confirm = (TransitionRules.Decision.Confirm) decision;
    if (command.transition() == AccountTransition.CLOSE && account.getBalanceMinorUnits() != 0L) {
      return reject(command, idempotencyKey, hash, actor, "account has non-zero balance");
    }
    account.applyStatus(confirm.status());
    accounts.save(account);
    idempotency.save(
        new TransitionIdempotency(
            idempotencyKey, command.accountId(), hash, TransitionOutcome.CONFIRMED));
    audit.record(
        new AuditEntry(
            actorSubject(actor),
            actionFor(command.transition()),
            "Account",
            command.accountId().toString(),
            "CONFIRMED",
            "from=" + previous + " to=" + confirm.status()));
    // Explicit flush surfaces uniqueness violations deterministically inside this transaction
    // on both H2 and PostgreSQL (same SQL constraint), instead of deferring them to commit
    // time where the catch could no longer reload and replay the winner.
    accounts.flush();
    return new AttemptOutcome.Confirmed(
        new TransitionResult(command.accountId(), confirm.status(), true));
  }

  private AttemptOutcome reject(
      TransitionCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor,
      String reason) {
    idempotency.save(
        new TransitionIdempotency(
            idempotencyKey, command.accountId(), hash, TransitionOutcome.REJECTED));
    audit.record(
        new AuditEntry(
            actorSubject(actor),
            "ACCOUNT_TRANSITION_REJECTED",
            "Account",
            idempotencyKey,
            "REJECTED",
            reason));
    return new AttemptOutcome.Rejected(reason);
  }

  /**
   * Same key with incompatible payload. The conflicting audit is recorded in its own write
   * transaction and the conflict is thrown only afterwards, so the evidence survives; no state
   * changes and idempotency state is untouched. Never called from inside a read-only transaction.
   */
  private TransitionResult conflict(String idempotencyKey, AuthenticatedActor actor) {
    writeTx.execute(
        status -> {
          audit.record(
              new AuditEntry(
                  actorSubject(actor),
                  "ACCOUNT_TRANSITION_CONFLICT",
                  "Account",
                  idempotencyKey,
                  "CONFLICT",
                  "idempotency key already used with incompatible payload"));
          return null;
        });
    throw new TransitionException(
        TransitionException.Kind.CONFLICT,
        "idempotency key already used with incompatible payload");
  }

  private TransitionResult replay(TransitionIdempotency stored) {
    if (stored.getOutcome() == TransitionOutcome.REJECTED) {
      throw new TransitionException(
          TransitionException.Kind.REJECTED, "request previously rejected");
    }
    // CONFIRMED covers both applied transitions and state-convergence no-ops, so the
    // resulting status is read back instead of inferred; a confirmed record always belongs
    // to a resolvable account (unknown accounts only ever reject).
    AccountStatus status =
        accounts
            .findById(stored.getAccountId())
            .map(Account::getStatus)
            .orElseThrow(
                () ->
                    new TransitionException(
                        TransitionException.Kind.FAILED, "confirmed account is not resolvable"));
    return new TransitionResult(stored.getAccountId(), status, false);
  }

  private String actionFor(AccountTransition transition) {
    return switch (transition) {
      case BLOCK -> "ACCOUNT_BLOCKED";
      case UNBLOCK -> "ACCOUNT_UNBLOCKED";
      case CLOSE -> "ACCOUNT_CLOSED";
    };
  }

  private String actorSubject(AuthenticatedActor actor) {
    return actor == null ? null : actor.subject();
  }

  private TransitionException forbidden(String reason) {
    if (reason != null && reason.contains("unauthenticated")) {
      return new TransitionException(TransitionException.Kind.UNAUTHENTICATED, reason);
    }
    return new TransitionException(TransitionException.Kind.FORBIDDEN, reason);
  }

  static String requestHash(TransitionCommand command) {
    try {
      String canonical =
          String.valueOf(command.accountId()).toLowerCase(Locale.ROOT)
              + "|"
              + String.valueOf(command.transition());
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new TransitionException(TransitionException.Kind.FAILED, "cannot hash request");
    }
  }

  /**
   * Outcome carrier: business rejections are thrown only after the write transaction commits, so
   * REJECTED records actually persist instead of being rolled back with the very transaction that
   * wrote them.
   */
  private sealed interface AttemptOutcome
      permits AttemptOutcome.Confirmed, AttemptOutcome.Rejected {

    record Confirmed(TransitionResult result) implements AttemptOutcome {}

    record Rejected(String reason) implements AttemptOutcome {}

    default TransitionResult resultOrThrow() {
      if (this instanceof Confirmed confirmed) {
        return confirmed.result();
      }
      throw new TransitionException(TransitionException.Kind.REJECTED, ((Rejected) this).reason());
    }
  }

  /**
   * Control flow: the post-lock re-check found a committed same-key record, so this attempt must
   * not write anything. Caught inside transition(), whose write transaction rolls back before the
   * winner is reloaded in a fresh read transaction.
   */
  private static final class DuplicateDetected extends RuntimeException {}
}
