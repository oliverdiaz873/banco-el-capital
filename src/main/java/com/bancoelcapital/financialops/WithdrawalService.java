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

import com.bancoelcapital.accounts.AccountDebitCommand;
import com.bancoelcapital.accounts.AccountDebitResult;
import com.bancoelcapital.accounts.AccountDebitService;
import com.bancoelcapital.accounts.AccountLookupService;
import com.bancoelcapital.audit.AuditEntry;
import com.bancoelcapital.audit.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * Withdrawal use case (Financial Operations module, ADR-02/ADR-04/ADR-16). Coordinates idempotency,
 * immutable debit movement creation, and balance application through the Accounts-owned debit
 * contract. Each confirmation runs in exactly one local transaction; the winner reload after a
 * uniqueness race runs in a separate fresh read transaction.
 *
 * <p>PostgreSQL aborts the whole transaction on any constraint violation, so a reload issued inside
 * the same transaction after a DataIntegrityViolationException could never succeed there. The write
 * attempt therefore runs in its own transaction, which the template rolls back on violation, and
 * the winner lookup runs afterwards in a brand-new read transaction against committed state.
 *
 * <p>UNKNOWN is caller-side uncertainty, never a persisted state: a client that loses the response
 * retries with the same Idempotency-Key and deterministically receives the stored result. FAILED is
 * thrown only for determined technical failures with no financial effect. Authorization belongs to
 * a later phase and is intentionally absent here. Audit events are recorded in the same local
 * transaction as the financial effects they describe, so a rolled-back confirmation never leaves a
 * false audit trail; replays record nothing new.
 */
@Service
public class WithdrawalService {

  private final FinancialOperationRepository operations;
  private final MovementRepository movements;
  private final WithdrawalOperationIdempotencyRepository idempotency;
  private final AccountDebitService debits;
  private final AccountLookupService lookup;
  private final AuthorizationService authorization;
  private final AuditRecorder audit;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  public WithdrawalService(
      FinancialOperationRepository operations,
      MovementRepository movements,
      WithdrawalOperationIdempotencyRepository idempotency,
      AccountDebitService debits,
      AccountLookupService lookup,
      AuthorizationService authorization,
      AuditRecorder audit,
      PlatformTransactionManager transactionManager) {
    this.operations = operations;
    this.movements = movements;
    this.idempotency = idempotency;
    this.debits = debits;
    this.lookup = lookup;
    this.authorization = authorization;
    this.audit = audit;
    this.writeTx = new TransactionTemplate(transactionManager);
    this.readTx = new TransactionTemplate(transactionManager);
    this.readTx.setReadOnly(true);
  }

  public WithdrawalResult withdraw(
      WithdrawalCommand command, String idempotencyKey, AuthenticatedActor actor) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new WithdrawalOperationException(
          WithdrawalOperationException.Kind.FAILED, "idempotency key is required");
    }
    // Account identity is server-resolved: the client supplies only the account UUID, never
    // the holder, so authorization cannot be satisfied with a mismatched holder.
    // Account UUIDs are unguessable; unknown ids deterministically reject like the
    // established "unknown holder" precedent instead of leaking through auth paths.
    Optional<String> holder = lookup.findHolderCustomerId(command.accountId());
    if (holder.isEmpty()) {
      // Committed rejection: the lambda returns normally so the write transaction commits
      // and the thrown REJECTED below happens outside of it.
      AttemptOutcome rejected =
          writeTx.execute(
              status ->
                  reject(
                      command,
                      idempotencyKey,
                      requestHash(command),
                      actorSubject(actor),
                      "unknown account"));
      return rejected.resultOrThrow();
    }
    var decision = authorization.decideWithdraw(actor, holder.get());
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    String hash = requestHash(command);
    Optional<WithdrawalOperationIdempotency> existing = idempotency.findById(idempotencyKey);
    if (existing.isPresent()) {
      if (!existing.get().getRequestHash().equals(hash)) {
        return conflict(idempotencyKey, actorSubject(actor));
      }
      return replay(existing.get());
    }
    final AttemptOutcome outcome;
    try {
      outcome =
          writeTx.execute(status -> attempt(command, idempotencyKey, hash, actorSubject(actor)));
    } catch (DuplicateDetected | DataIntegrityViolationException race) {
      // Our own write transaction rolled back with nothing persisted: either a concurrent
      // same-key attempt committed while we waited on the account row lock (detected by the
      // post-lock re-check), or the database arbiter rejected our insert. Reload the winner
      // in a fresh read transaction; what we read there is committed state on every engine.
      Optional<WithdrawalOperationIdempotency> winner =
          readTx.execute(status -> idempotency.findById(idempotencyKey));
      if (winner.isEmpty()) {
        // Nothing determined: the winner may still be in flight. The caller is uncertain,
        // not failed; recovery reuses the same key.
        throw new WithdrawalOperationException(
            WithdrawalOperationException.Kind.UNKNOWN,
            "uncertain outcome, retry with the same idempotency key");
      }
      if (!winner.get().getRequestHash().equals(hash)) {
        return conflict(idempotencyKey, actorSubject(actor));
      }
      return replay(winner.get());
    } catch (ArithmeticException overflow) {
      // Arithmetic failure rolled the write transaction back: determined, no effect.
      throw new WithdrawalOperationException(
          WithdrawalOperationException.Kind.FAILED, "arithmetic failure, no effect applied");
    }
    return outcome.resultOrThrow();
  }

  @Transactional(readOnly = true)
  public Optional<WithdrawalOperationOutcome> outcomeOf(String idempotencyKey) {
    return idempotency.findById(idempotencyKey).map(WithdrawalOperationIdempotency::getOutcome);
  }

  /**
   * Queryable outcome for timeout/disconnect recovery. Never re-executes the withdrawal and never
   * mutates state. The caller MUST be authorized for the operation's holder; hash mismatch and
   * absence both resolve to empty without leaking which one occurred.
   */
  @Transactional(readOnly = true)
  public Optional<WithdrawalOutcome> findAuthorizedByKey(
      String idempotencyKey, String requestHash, AuthenticatedActor actor) {
    Optional<WithdrawalOperationIdempotency> stored = idempotency.findById(idempotencyKey);
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
    var queryDecision = authorization.decideWithdraw(actor, holder.get());
    if (!queryDecision.allowed()) {
      throw forbidden(queryDecision.reason());
    }
    return Optional.of(new WithdrawalOutcome(record.getOperationId(), record.getOutcome()));
  }

  /**
   * Runs inside exactly one local write transaction. Returns normally (never throws business
   * outcomes) so REJECTED records actually commit; technical failures propagate and roll back. The
   * debit contract write-locks the account row, so the funds check and the balance mutation are
   * atomic with respect to concurrent withdrawals.
   */
  private AttemptOutcome attempt(
      WithdrawalCommand command, String idempotencyKey, String hash, String actorSubject) {
    final AccountDebitResult debit;
    debit =
        debits.applyDebit(
            new AccountDebitCommand(
                command.accountId(), command.amountMinorUnits(), command.currency()));
    if (!debit.applied()) {
      return reject(command, idempotencyKey, hash, actorSubject, reasonFor(debit.outcome()));
    }
    // Post-lock re-check: applyDebit serialized us on the account row, so a concurrent
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
            FinancialOperationType.WITHDRAWAL,
            actorSubject,
            command.accountId(),
            command.amountMinorUnits(),
            command.currency(),
            FinancialOperationStatus.CONFIRMED));
    movements.save(
        new Movement(
            UUID.randomUUID(),
            operationId,
            command.accountId(),
            MovementDirection.DEBIT,
            command.amountMinorUnits(),
            command.currency()));
    idempotency.save(
        new WithdrawalOperationIdempotency(
            idempotencyKey, operationId, hash, WithdrawalOperationOutcome.CONFIRMED));
    audit.record(
        new AuditEntry(
            actorSubject,
            "WITHDRAWAL_CONFIRMED",
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
    return new AttemptOutcome.Confirmed(new WithdrawalResult(operationId, true));
  }

  private AttemptOutcome reject(
      WithdrawalCommand command,
      String idempotencyKey,
      String hash,
      String actorSubject,
      String reason) {
    UUID operationId = UUID.randomUUID();
    operations.save(
        new FinancialOperation(
            operationId,
            FinancialOperationType.WITHDRAWAL,
            actorSubject,
            command.accountId(),
            command.amountMinorUnits(),
            command.currency(),
            FinancialOperationStatus.REJECTED));
    idempotency.save(
        new WithdrawalOperationIdempotency(
            idempotencyKey, operationId, hash, WithdrawalOperationOutcome.REJECTED));
    audit.record(
        new AuditEntry(
            actorSubject,
            "WITHDRAWAL_REJECTED",
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
  private WithdrawalResult conflict(String idempotencyKey, String actorSubject) {
    writeTx.execute(
        status -> {
          audit.record(
              new AuditEntry(
                  actorSubject,
                  "WITHDRAWAL_CONFLICT",
                  "FinancialOperation",
                  idempotencyKey,
                  "CONFLICT",
                  "idempotency key already used with incompatible payload"));
          return null;
        });
    throw new WithdrawalOperationException(
        WithdrawalOperationException.Kind.CONFLICT,
        "idempotency key already used with incompatible payload");
  }

  private WithdrawalResult replay(WithdrawalOperationIdempotency stored) {
    if (stored.getOutcome() == WithdrawalOperationOutcome.REJECTED) {
      throw new WithdrawalOperationException(
          WithdrawalOperationException.Kind.REJECTED, "request previously rejected");
    }
    return new WithdrawalResult(stored.getOperationId(), false);
  }

  private String reasonFor(AccountDebitResult.AccountDebitOutcome outcome) {
    return switch (outcome) {
      case ACCOUNT_NOT_FOUND -> "unknown account";
      case NOT_OPERABLE -> "account not operable for withdrawals";
      case CURRENCY_MISMATCH -> "currency must match the account currency";
      case INVALID_AMOUNT -> "amount must be a positive minor-units value";
      case INSUFFICIENT_FUNDS -> "insufficient funds";
      case APPLIED -> throw new IllegalStateException("applied is not a rejection");
    };
  }

  private String actorSubject(AuthenticatedActor actor) {
    return actor == null ? null : actor.subject();
  }

  private WithdrawalOperationException forbidden(String reason) {
    if (reason != null && reason.contains("unauthenticated")) {
      return new WithdrawalOperationException(
          WithdrawalOperationException.Kind.UNAUTHENTICATED, reason);
    }
    return new WithdrawalOperationException(WithdrawalOperationException.Kind.FORBIDDEN, reason);
  }

  static String requestHash(WithdrawalCommand command) {
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
      throw new WithdrawalOperationException(
          WithdrawalOperationException.Kind.FAILED, "cannot hash request");
    }
  }

  /**
   * Outcome carrier: business rejections are thrown only after the write transaction commits, so
   * REJECTED operation and idempotency records actually persist instead of being rolled back with
   * the very transaction that wrote them.
   */
  private sealed interface AttemptOutcome
      permits AttemptOutcome.Confirmed, AttemptOutcome.Rejected {

    record Confirmed(WithdrawalResult result) implements AttemptOutcome {}

    record Rejected(String reason) implements AttemptOutcome {}

    default WithdrawalResult resultOrThrow() {
      if (this instanceof Confirmed confirmed) {
        return confirmed.result();
      }
      throw new WithdrawalOperationException(
          WithdrawalOperationException.Kind.REJECTED, ((Rejected) this).reason());
    }
  }

  /**
   * Control flow: the post-lock re-check found a committed same-key record, so this attempt must
   * not write anything. Caught inside withdraw(), whose write transaction rolls back the unflushed
   * balance mutation before the winner is reloaded in a fresh read transaction.
   */
  private static final class DuplicateDetected extends RuntimeException {}
}
