package com.bancoelcapital.financialops.transfer;

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

import com.bancoelcapital.accounts.api.AccountCreditCommand;
import com.bancoelcapital.accounts.api.AccountCreditResult;
import com.bancoelcapital.accounts.api.AccountCreditService;
import com.bancoelcapital.accounts.api.AccountDebitCommand;
import com.bancoelcapital.accounts.api.AccountDebitResult;
import com.bancoelcapital.accounts.api.AccountDebitService;
import com.bancoelcapital.accounts.api.AccountLookupService;
import com.bancoelcapital.audit.api.AuditEntry;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.financialops.core.FinancialOperation;
import com.bancoelcapital.financialops.core.FinancialOperationRepository;
import com.bancoelcapital.financialops.core.FinancialOperationStatus;
import com.bancoelcapital.financialops.core.FinancialOperationType;
import com.bancoelcapital.financialops.core.Movement;
import com.bancoelcapital.financialops.core.MovementDirection;
import com.bancoelcapital.financialops.core.MovementRepository;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * Transfer use case (Financial Operations module, ADR-02/ADR-04/ADR-05/ADR-18). Coordinates
 * authorization on the source holder, validation of both accounts, money-grade idempotency in a
 * dedicated namespace, immutable debit plus credit movement creation, and balance application
 * through the Accounts-owned contracts. Each confirmation runs in exactly one outer local
 * transaction; the winner reload after a uniqueness race runs in a separate fresh read transaction
 * (see below).
 *
 * <p>Both account rows are locked in deterministic UUID ascending order, regardless of transfer
 * direction, so opposite transfers cannot deadlock. The confirmation decision is taken only after
 * both contract results are known. A business rejection after the first leg applied rolls that leg
 * back and persists the rejection in a fresh write transaction, so a rejected transfer never leaves
 * a partial effect behind.
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
public class TransferService {

  private final FinancialOperationRepository operations;
  private final MovementRepository movements;
  private final TransferOperationIdempotencyRepository idempotency;
  private final AccountLookupService lookup;
  private final AccountCreditService credits;
  private final AccountDebitService debits;
  private final AuthorizationService authorization;
  private final AuditRecorder audit;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  public TransferService(
      FinancialOperationRepository operations,
      MovementRepository movements,
      TransferOperationIdempotencyRepository idempotency,
      AccountLookupService lookup,
      AccountCreditService credits,
      AccountDebitService debits,
      AuthorizationService authorization,
      AuditRecorder audit,
      PlatformTransactionManager transactionManager) {
    this.operations = operations;
    this.movements = movements;
    this.idempotency = idempotency;
    this.lookup = lookup;
    this.credits = credits;
    this.debits = debits;
    this.authorization = authorization;
    this.audit = audit;
    this.writeTx = new TransactionTemplate(transactionManager);
    this.readTx = new TransactionTemplate(transactionManager);
    this.readTx.setReadOnly(true);
  }

  public TransferResult transfer(
      TransferCommand command, String idempotencyKey, AuthenticatedActor actor) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new TransferOperationException(
          TransferOperationException.Kind.FAILED, "idempotency key is required");
    }
    if (command.sourceAccountId() != null
        && command.sourceAccountId().equals(command.destinationAccountId())) {
      String hash = requestHash(command);
      return writeTx
          .execute(
              status ->
                  reject(
                      command, idempotencyKey, hash, actor, "source and destination must differ"))
          .resultOrThrow();
    }
    // Account identities are server-resolved: the client supplies only account UUIDs, never
    // holders, so authorization cannot be satisfied with a mismatched holder.
    // Account UUIDs are unguessable; unknown ids deterministically reject like the
    // established "unknown holder" precedent instead of leaking through auth paths.
    Optional<String> sourceHolder = lookup.findHolderCustomerId(command.sourceAccountId());
    if (sourceHolder.isEmpty()) {
      String hash = requestHash(command);
      return writeTx
          .execute(status -> reject(command, idempotencyKey, hash, actor, "unknown account"))
          .resultOrThrow();
    }
    Optional<String> destinationHolder =
        lookup.findHolderCustomerId(command.destinationAccountId());
    if (destinationHolder.isEmpty()) {
      String hash = requestHash(command);
      return writeTx
          .execute(status -> reject(command, idempotencyKey, hash, actor, "unknown account"))
          .resultOrThrow();
    }
    var decision = authorization.decideTransfer(actor, sourceHolder.get());
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    String hash = requestHash(command);
    Optional<TransferOperationIdempotency> existing = idempotency.findById(idempotencyKey);
    if (existing.isPresent()) {
      if (!existing.get().getRequestHash().equals(hash)) {
        return conflict(idempotencyKey, actor);
      }
      return replay(existing.get());
    }
    final AttemptOutcome outcome;
    try {
      outcome = writeTx.execute(status -> attempt(command, idempotencyKey, hash, actor));
    } catch (LegRejected rejected) {
      // The first leg applied and the second leg refused: our write transaction rolled back,
      // discarding the partial balance effect. Persist the deterministic rejection in a fresh
      // write transaction that touches no balances.
      final AttemptOutcome rejection;
      try {
        rejection =
            writeTx.execute(
                status -> reject(command, idempotencyKey, hash, actor, rejected.getReason()));
      } catch (DataIntegrityViolationException collision) {
        // A concurrent same-key winner committed between our re-check and this insert: our
        // transaction rolled back with nothing persisted. Resolve through the existing
        // recovery path instead of leaking a persistence exception — but only when the
        // winner actually exists. Without a winner there is no financial result to return,
        // so the original collision propagates untouched.
        Optional<TransferOperationIdempotency> lateWinner =
            readTx.execute(status -> idempotency.findById(idempotencyKey));
        if (lateWinner.isEmpty()) {
          throw collision;
        }
        if (!lateWinner.get().getRequestHash().equals(hash)) {
          return conflict(idempotencyKey, actor);
        }
        return replay(lateWinner.get());
      }
      return rejection.resultOrThrow();
    } catch (DuplicateDetected | DataIntegrityViolationException race) {
      // Our own write transaction rolled back with nothing persisted: either a concurrent
      // same-key attempt committed while we waited on the account row locks (detected by the
      // post-lock re-check), or the database arbiter rejected our insert. Reload the winner
      // in a fresh read transaction; what we read there is committed state on every engine.
      Optional<TransferOperationIdempotency> winner =
          readTx.execute(status -> idempotency.findById(idempotencyKey));
      if (winner.isEmpty()) {
        // Nothing determined: the winner may still be in flight. The caller is uncertain,
        // not failed; recovery reuses the same key.
        throw new TransferOperationException(
            TransferOperationException.Kind.UNKNOWN,
            "uncertain outcome, retry with the same idempotency key");
      }
      if (!winner.get().getRequestHash().equals(hash)) {
        return conflict(idempotencyKey, actor);
      }
      return replay(winner.get());
    } catch (ArithmeticException overflow) {
      // Balance overflow rolled the write transaction back: determined, no effect.
      throw new TransferOperationException(
          TransferOperationException.Kind.FAILED, "arithmetic overflow, no effect applied");
    }
    return outcome.resultOrThrow();
  }

  @Transactional(readOnly = true)
  public Optional<TransferOperationOutcome> outcomeOf(String idempotencyKey) {
    return idempotency.findById(idempotencyKey).map(TransferOperationIdempotency::getOutcome);
  }

  /**
   * Queryable outcome for timeout/disconnect recovery. Never re-executes the transfer and never
   * mutates state. The caller MUST be authorized for the source holder (Option A: the operation row
   * identifies the source); the destination holder alone learns nothing. Hash mismatch and absence
   * both resolve to empty without leaking which one occurred.
   */
  @Transactional(readOnly = true)
  public Optional<TransferOutcome> findAuthorizedByKey(
      String idempotencyKey, String requestHash, AuthenticatedActor actor) {
    Optional<TransferOperationIdempotency> stored = idempotency.findById(idempotencyKey);
    if (stored.isEmpty() || !stored.get().getRequestHash().equals(requestHash)) {
      return Optional.empty();
    }
    var record = stored.get();
    Optional<FinancialOperation> operation = operations.findById(record.getOperationId());
    if (operation.isEmpty()) {
      return Optional.empty();
    }
    Optional<String> sourceHolder = lookup.findHolderCustomerId(operation.get().getAccountId());
    if (sourceHolder.isEmpty()) {
      return Optional.empty();
    }
    var queryDecision = authorization.decideTransfer(actor, sourceHolder.get());
    if (!queryDecision.allowed()) {
      throw forbidden(queryDecision.reason());
    }
    return Optional.of(new TransferOutcome(record.getOperationId(), record.getOutcome()));
  }

  /**
   * Runs inside exactly one outer local write transaction. Both Accounts-owned contracts join that
   * same transaction (REQUIRED propagation): the row locks are acquired in UUID ascending order and
   * held until commit. Returns normally (never throws business outcomes) so REJECTED records
   * actually commit; technical failures propagate and roll back.
   */
  private AttemptOutcome attempt(
      TransferCommand command, String idempotencyKey, String hash, AuthenticatedActor actor) {
    boolean sourceFirst =
        command.sourceAccountId() == null
            || command.destinationAccountId() == null
            || command.sourceAccountId().compareTo(command.destinationAccountId()) < 0;
    if (sourceFirst) {
      AccountDebitResult debit =
          debits.applyDebit(
              new AccountDebitCommand(
                  command.sourceAccountId(), command.amountMinorUnits(), command.currency()));
      if (!debit.applied()) {
        return reject(command, idempotencyKey, hash, actor, reasonForDebit(debit.outcome()));
      }
      AccountCreditResult credit =
          credits.applyCredit(
              new AccountCreditCommand(
                  command.destinationAccountId(), command.amountMinorUnits(), command.currency()));
      if (!credit.applied()) {
        // The debit leg already mutated the source balance inside this transaction: it must not
        // commit. Re-check idempotency first (both locks are held, so a concurrent same-key
        // winner is committed state now) and replay it instead of persisting a competing
        // rejection; only with no winner in sight does the refusal become a LegRejected that
        // rolls the whole attempt back for a fresh balance-free rejection.
        throw secondLegRefusal(idempotencyKey, reasonForCredit(credit.outcome()));
      }
    } else {
      AccountCreditResult credit =
          credits.applyCredit(
              new AccountCreditCommand(
                  command.destinationAccountId(), command.amountMinorUnits(), command.currency()));
      if (!credit.applied()) {
        return reject(command, idempotencyKey, hash, actor, reasonForCredit(credit.outcome()));
      }
      AccountDebitResult debit =
          debits.applyDebit(
              new AccountDebitCommand(
                  command.sourceAccountId(), command.amountMinorUnits(), command.currency()));
      if (!debit.applied()) {
        throw secondLegRefusal(idempotencyKey, reasonForDebit(debit.outcome()));
      }
    }
    // Post-lock re-check: both row locks are now held, so a concurrent same-key attempt may
    // have committed while we waited. Its idempotency row is committed state now and visible
    // to this fresh read on every engine; bailing here keeps us from writing a second
    // financial effect that a later uniqueness check might miss.
    if (idempotency.findById(idempotencyKey).isPresent()) {
      throw new DuplicateDetected();
    }
    UUID operationId = UUID.randomUUID();
    operations.save(
        new FinancialOperation(
            operationId,
            FinancialOperationType.TRANSFER,
            actorSubject(actor),
            command.sourceAccountId(),
            command.amountMinorUnits(),
            command.currency(),
            FinancialOperationStatus.CONFIRMED));
    movements.save(
        new Movement(
            UUID.randomUUID(),
            operationId,
            command.sourceAccountId(),
            MovementDirection.DEBIT,
            command.amountMinorUnits(),
            command.currency()));
    movements.save(
        new Movement(
            UUID.randomUUID(),
            operationId,
            command.destinationAccountId(),
            MovementDirection.CREDIT,
            command.amountMinorUnits(),
            command.currency()));
    idempotency.save(
        new TransferOperationIdempotency(
            idempotencyKey, operationId, hash, TransferOperationOutcome.CONFIRMED));
    audit.record(
        new AuditEntry(
            actorSubject(actor),
            "TRANSFER_CONFIRMED",
            "FinancialOperation",
            operationId.toString(),
            "CONFIRMED",
            "source="
                + command.sourceAccountId()
                + " destination="
                + command.destinationAccountId()
                + " amount="
                + command.amountMinorUnits()
                + " currency="
                + command.currency()));
    // Explicit flush surfaces uniqueness violations deterministically inside this transaction
    // on both H2 and PostgreSQL (same SQL constraint), instead of deferring them to commit
    // time where the catch could no longer reload and replay the winner.
    operations.flush();
    return new AttemptOutcome.Confirmed(new TransferResult(operationId, true));
  }

  private AttemptOutcome reject(
      TransferCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor,
      String reason) {
    UUID operationId = UUID.randomUUID();
    operations.save(
        new FinancialOperation(
            operationId,
            FinancialOperationType.TRANSFER,
            actorSubject(actor),
            command.sourceAccountId(),
            command.amountMinorUnits(),
            command.currency(),
            FinancialOperationStatus.REJECTED));
    idempotency.save(
        new TransferOperationIdempotency(
            idempotencyKey, operationId, hash, TransferOperationOutcome.REJECTED));
    audit.record(
        new AuditEntry(
            actorSubject(actor),
            "TRANSFER_REJECTED",
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
  private TransferResult conflict(String idempotencyKey, AuthenticatedActor actor) {
    writeTx.execute(
        status -> {
          audit.record(
              new AuditEntry(
                  actorSubject(actor),
                  "TRANSFER_CONFLICT",
                  "FinancialOperation",
                  idempotencyKey,
                  "CONFLICT",
                  "idempotency key already used with incompatible payload"));
          return null;
        });
    throw new TransferOperationException(
        TransferOperationException.Kind.CONFLICT,
        "idempotency key already used with incompatible payload");
  }

  private TransferResult replay(TransferOperationIdempotency stored) {
    if (stored.getOutcome() == TransferOperationOutcome.REJECTED) {
      throw new TransferOperationException(
          TransferOperationException.Kind.REJECTED, "request previously rejected");
    }
    return new TransferResult(stored.getOperationId(), false);
  }

  private String reasonForDebit(AccountDebitResult.AccountDebitOutcome outcome) {
    return switch (outcome) {
      case ACCOUNT_NOT_FOUND -> "unknown account";
      case NOT_OPERABLE -> "source account not operable for transfers";
      case CURRENCY_MISMATCH -> "currency must match the source account currency";
      case INVALID_AMOUNT -> "amount must be a positive minor-units value";
      case INSUFFICIENT_FUNDS -> "insufficient funds";
      case APPLIED -> throw new IllegalStateException("applied is not a rejection");
    };
  }

  private String reasonForCredit(AccountCreditResult.AccountCreditOutcome outcome) {
    return switch (outcome) {
      case ACCOUNT_NOT_FOUND -> "unknown account";
      case NOT_OPERABLE -> "destination account not operable for transfers";
      case CURRENCY_MISMATCH -> "currency must match the destination account currency";
      case INVALID_AMOUNT -> "amount must be a positive minor-units value";
      case APPLIED -> throw new IllegalStateException("applied is not a rejection");
    };
  }

  private String actorSubject(AuthenticatedActor actor) {
    return actor == null ? null : actor.subject();
  }

  private TransferOperationException forbidden(String reason) {
    if (reason != null && reason.contains("unauthenticated")) {
      return new TransferOperationException(
          TransferOperationException.Kind.UNAUTHENTICATED, reason);
    }
    return new TransferOperationException(TransferOperationException.Kind.FORBIDDEN, reason);
  }

  static String requestHash(TransferCommand command) {
    try {
      String canonical =
          String.valueOf(command.sourceAccountId()).toLowerCase(Locale.ROOT)
              + "|"
              + String.valueOf(command.destinationAccountId()).toLowerCase(Locale.ROOT)
              + "|"
              + String.valueOf(command.amountMinorUnits())
              + "|"
              + String.valueOf(command.currency()).toUpperCase(Locale.ROOT);
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new TransferOperationException(
          TransferOperationException.Kind.FAILED, "cannot hash request");
    }
  }

  /**
   * Outcome carrier: business rejections are thrown only after the write transaction commits, so
   * REJECTED operation and idempotency records actually persist instead of being rolled back with
   * the very transaction that wrote them.
   */
  private sealed interface AttemptOutcome
      permits AttemptOutcome.Confirmed, AttemptOutcome.Rejected {

    record Confirmed(TransferResult result) implements AttemptOutcome {}

    record Rejected(String reason) implements AttemptOutcome {}

    default TransferResult resultOrThrow() {
      if (this instanceof Confirmed confirmed) {
        return confirmed.result();
      }
      throw new TransferOperationException(
          TransferOperationException.Kind.REJECTED, ((Rejected) this).reason());
    }
  }

  /**
   * Second-leg refusal with winner awareness. Both row locks are held here, so a concurrent
   * same-key attempt that committed while we waited is committed state now: throw DuplicateDetected
   * so the caller replays it instead of persisting a competing rejection. Only with no winner in
   * sight does the refusal become a LegRejected that rolls the whole attempt back for a fresh
   * balance-free rejection.
   */
  private RuntimeException secondLegRefusal(String idempotencyKey, String reason) {
    if (idempotency.findById(idempotencyKey).isPresent()) {
      throw new DuplicateDetected();
    }
    throw new LegRejected(reason);
  }

  /**
   * Control flow: the second leg refused after the first leg already mutated its balance inside the
   * same write transaction. Caught inside transfer(), whose write transaction rolls back the
   * partial effect before the rejection is persisted in a fresh balance-free transaction.
   */
  private static final class LegRejected extends RuntimeException {

    private final String reason;

    LegRejected(String reason) {
      super(reason);
      this.reason = reason;
    }

    String getReason() {
      return reason;
    }
  }

  /**
   * Control flow: the post-lock re-check found a committed same-key record, so this attempt must
   * not write anything. Caught inside transfer(), whose write transaction rolls back the unflushed
   * balance mutations before the winner is reloaded in a fresh read transaction.
   */
  private static final class DuplicateDetected extends RuntimeException {}
}
