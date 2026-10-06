package com.bancoelcapital.financialops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bancoelcapital.accounts.AccountCreditCommand;
import com.bancoelcapital.accounts.AccountCreditResult;
import com.bancoelcapital.accounts.AccountCreditService;
import com.bancoelcapital.accounts.AccountLookupService;
import com.bancoelcapital.audit.AuditEntry;
import com.bancoelcapital.audit.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * Deposit use case (Financial Operations module, ADR-02/ADR-04/ADR-15). Coordinates authorization,
 * validation, idempotency, immutable movement creation, and balance application through the
 * Accounts-owned contract. Each confirmation runs in exactly one local transaction; the winner
 * reload after a uniqueness race runs in a separate fresh read transaction (see below).
 *
 * <p>PostgreSQL aborts the whole transaction on any constraint violation, so a reload issued inside
 * the same transaction after a DataIntegrityViolationException could never succeed there. The write
 * attempt therefore runs in its own transaction, which the template rolls back on violation, and
 * the winner lookup runs afterwards in a brand-new read transaction against committed state. This
 * relies only on standard local-transaction semantics, identical on H2 and PostgreSQL.
 *
 * <p>UNKNOWN is caller-side uncertainty, never a persisted state: a client that loses the response
 * retries with the same Idempotency-Key and deterministically receives the stored result. FAILED is
 * thrown only for determined technical failures with no financial effect.
 */
@Service
public class DepositService {

  private final FinancialOperationRepository operations;
  private final MovementRepository movements;
  private final DepositOperationIdempotencyRepository idempotency;
  private final AccountLookupService lookup;
  private final AccountCreditService credits;
  private final AuthorizationService authorization;
  private final AuditRecorder audit;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  public DepositService(
      FinancialOperationRepository operations,
      MovementRepository movements,
      DepositOperationIdempotencyRepository idempotency,
      AccountLookupService lookup,
      AccountCreditService credits,
      AuthorizationService authorization,
      AuditRecorder audit,
      PlatformTransactionManager transactionManager) {
    this.operations = operations;
    this.movements = movements;
    this.idempotency = idempotency;
    this.lookup = lookup;
    this.credits = credits;
    this.authorization = authorization;
    this.audit = audit;
    this.writeTx = new TransactionTemplate(transactionManager);
    this.readTx = new TransactionTemplate(transactionManager);
    this.readTx.setReadOnly(true);
  }

  public DepositResult deposit(
      DepositCommand command, String idempotencyKey, AuthenticatedActor actor) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new DepositOperationException(
          DepositOperationException.Kind.FAILED, "idempotency key is required");
    }
    // Account identity is server-resolved: the client supplies only the account UUID, never
    // the holder, so authorization cannot be satisfied with a mismatched holder.
    // Account UUIDs are unguessable; unknown ids deterministically reject like the
    // established "unknown holder" precedent instead of leaking through auth paths.
    Optional<String> holder = lookup.findHolderCustomerId(command.accountId());
    if (holder.isEmpty()) {
      return writeTx.execute(
          status ->
              reject(command, idempotencyKey, requestHash(command), actor, "unknown account")
                  .resultOrThrow());
    }
    var decision = authorization.decideDeposit(actor, holder.get());
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    String hash = requestHash(command);
    Optional<DepositOperationIdempotency> existing = idempotency.findById(idempotencyKey);
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
      Optional<DepositOperationIdempotency> winner =
          readTx.execute(status -> idempotency.findById(idempotencyKey));
      if (winner.isEmpty()) {
        // Nothing determined: the winner may still be in flight. The caller is uncertain,
        // not failed; recovery reuses the same key.
        throw new DepositOperationException(
            DepositOperationException.Kind.UNKNOWN,
            "uncertain outcome, retry with the same idempotency key");
      }
      if (!winner.get().getRequestHash().equals(hash)) {
        return conflict(idempotencyKey, actor);
      }
      return replay(winner.get());
    } catch (ArithmeticException overflow) {
      // Balance overflow rolled the write transaction back: determined, no effect.
      throw new DepositOperationException(
          DepositOperationException.Kind.FAILED, "arithmetic overflow, no effect applied");
    }
    return outcome.resultOrThrow();
  }

  @Transactional(readOnly = true)
  public Optional<DepositOperationOutcome> outcomeOf(String idempotencyKey) {
    return idempotency.findById(idempotencyKey).map(DepositOperationIdempotency::getOutcome);
  }

  /**
   * Queryable outcome for timeout/disconnect recovery. Never re-executes the deposit and never
   * mutates state. The caller MUST be authorized for the operation's holder; hash mismatch and
   * absence both resolve to empty without leaking which one occurred.
   */
  @Transactional(readOnly = true)
  public Optional<DepositOutcome> findAuthorizedByKey(
      String idempotencyKey, String requestHash, AuthenticatedActor actor) {
    Optional<DepositOperationIdempotency> stored = idempotency.findById(idempotencyKey);
    if (stored.isEmpty() || !stored.get().getRequestHash().equals(requestHash)) {
      return Optional.empty();
    }
    var record = stored.get();
    Optional<FinancialOperation> operation = operations.findById(record.getOperationId());
    if (operation.isEmpty()) {
      return Optional.empty();
    }
    Optional<String> holder = lookup.findHolderCustomerId(operation.get().getAccountId());
    if (holder.isEmpty()) {
      return Optional.empty();
    }
    var queryDecision = authorization.decideDeposit(actor, holder.get());
    if (!queryDecision.allowed()) {
      throw forbidden(queryDecision.reason());
    }
    return Optional.of(new DepositOutcome(record.getOperationId(), record.getOutcome()));
  }

  /**
   * Runs inside exactly one local write transaction. Returns normally (never throws business
   * outcomes) so REJECTED records actually commit; technical failures propagate and roll back.
   */
  private AttemptOutcome attempt(
      DepositCommand command, String idempotencyKey, String hash, AuthenticatedActor actor) {
    final AccountCreditResult credit;
    credit =
        credits.applyCredit(
            new AccountCreditCommand(
                command.accountId(), command.amountMinorUnits(), command.currency()));
    if (!credit.applied()) {
      return reject(command, idempotencyKey, hash, actor, reasonFor(credit.outcome()));
    }
    // Post-lock re-check: applyCredit serialized us on the account row, so a concurrent
    // same-key attempt may have committed while we waited. Its idempotency row is committed
    // state now and visible to this fresh read on every engine; bailing here keeps us from
    // writing a second financial effect that a later uniqueness check might miss.
    if (idempotency.findById(idempotencyKey).isPresent()) {
      throw new DuplicateDetected();
    }
    UUID operationId = UUID.randomUUID();
    operations.save(
        new FinancialOperation(
            operationId,
            FinancialOperationType.DEPOSIT,
            actor.subject(),
            command.accountId(),
            command.amountMinorUnits(),
            command.currency(),
            FinancialOperationStatus.CONFIRMED));
    movements.save(
        new Movement(
            UUID.randomUUID(),
            operationId,
            command.accountId(),
            MovementDirection.CREDIT,
            command.amountMinorUnits(),
            command.currency()));
    idempotency.save(
        new DepositOperationIdempotency(
            idempotencyKey, operationId, hash, DepositOperationOutcome.CONFIRMED));
    audit.record(
        new AuditEntry(
            actor.subject(),
            "DEPOSIT_CONFIRMED",
            "FinancialOperation",
            operationId.toString(),
            "CONFIRMED",
            "account="
                + command.accountId()
                + " amount="
                + command.amountMinorUnits()
                + " currency="
                + command.currency()));
    // Explicit flush surfaces uniqueness violations deterministically inside this transaction
    // on both H2 and PostgreSQL (same SQL constraint), instead of deferring them to commit
    // time where the catch could no longer reload and replay the winner.
    operations.flush();
    return new AttemptOutcome.Confirmed(new DepositResult(operationId, true));
  }

  private AttemptOutcome reject(
      DepositCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor,
      String reason) {
    UUID operationId = UUID.randomUUID();
    operations.save(
        new FinancialOperation(
            operationId,
            FinancialOperationType.DEPOSIT,
            actor.subject(),
            command.accountId(),
            command.amountMinorUnits(),
            command.currency(),
            FinancialOperationStatus.REJECTED));
    idempotency.save(
        new DepositOperationIdempotency(
            idempotencyKey, operationId, hash, DepositOperationOutcome.REJECTED));
    audit.record(
        new AuditEntry(
            actor.subject(),
            "DEPOSIT_REJECTED",
            "FinancialOperation",
            idempotencyKey,
            "REJECTED",
            reason));
    return new AttemptOutcome.Rejected(reason);
  }

  /**
   * Same key with incompatible payload. The conflicting audit is recorded in its own write
   * transaction and the conflict is thrown only afterwards, so the evidence survives; no money
   * moves and idempotency state is untouched. Never called from inside a read-only transaction.
   */
  private DepositResult conflict(String idempotencyKey, AuthenticatedActor actor) {
    writeTx.execute(
        status -> {
          audit.record(
              new AuditEntry(
                  actor.subject(),
                  "DEPOSIT_CONFLICT",
                  "FinancialOperation",
                  idempotencyKey,
                  "CONFLICT",
                  "idempotency key already used with incompatible payload"));
          return null;
        });
    throw new DepositOperationException(
        DepositOperationException.Kind.CONFLICT,
        "idempotency key already used with incompatible payload");
  }

  private DepositResult replay(DepositOperationIdempotency stored) {
    if (stored.getOutcome() == DepositOperationOutcome.REJECTED) {
      throw new DepositOperationException(
          DepositOperationException.Kind.REJECTED, "request previously rejected");
    }
    return new DepositResult(stored.getOperationId(), false);
  }

  private String reasonFor(AccountCreditResult.AccountCreditOutcome outcome) {
    return switch (outcome) {
      case ACCOUNT_NOT_FOUND -> "unknown account";
      case NOT_OPERABLE -> "account not operable for deposits";
      case CURRENCY_MISMATCH -> "currency must match the account currency";
      case INVALID_AMOUNT -> "amount must be a positive minor-units value";
      case APPLIED -> throw new IllegalStateException("applied is not a rejection");
    };
  }

  private DepositOperationException forbidden(String reason) {
    if (reason != null && reason.contains("unauthenticated")) {
      return new DepositOperationException(DepositOperationException.Kind.UNAUTHENTICATED, reason);
    }
    return new DepositOperationException(DepositOperationException.Kind.FORBIDDEN, reason);
  }

  static String requestHash(DepositCommand command) {
    try {
      String canonical =
          String.valueOf(command.accountId())
              + "|"
              + String.valueOf(command.amountMinorUnits())
              + "|"
              + String.valueOf(command.currency());
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new DepositOperationException(
          DepositOperationException.Kind.FAILED, "cannot hash request");
    }
  }

  /**
   * Outcome carrier: business rejections are thrown only after the write transaction commits, so
   * REJECTED operation and idempotency records actually persist instead of being rolled back with
   * the very transaction that wrote them.
   */
  private sealed interface AttemptOutcome
      permits AttemptOutcome.Confirmed, AttemptOutcome.Rejected {

    record Confirmed(DepositResult result) implements AttemptOutcome {}

    record Rejected(String reason) implements AttemptOutcome {}

    default DepositResult resultOrThrow() {
      if (this instanceof Confirmed confirmed) {
        return confirmed.result();
      }
      throw new DepositOperationException(
          DepositOperationException.Kind.REJECTED, ((Rejected) this).reason());
    }
  }

  /**
   * Control flow: the post-lock re-check found a committed same-key record, so this attempt must
   * not write anything. Caught inside deposit(), whose write transaction rolls back the unflushed
   * balance mutation before the winner is reloaded in a fresh read transaction.
   */
  private static final class DuplicateDetected extends RuntimeException {}
}
