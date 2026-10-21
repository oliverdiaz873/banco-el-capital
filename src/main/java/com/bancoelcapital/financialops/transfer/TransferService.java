package com.bancoelcapital.financialops.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
import com.bancoelcapital.beneficiaries.api.BeneficiaryResolution;
import com.bancoelcapital.beneficiaries.api.BeneficiaryResolver;
import com.bancoelcapital.financialops.core.FinancialOperation;
import com.bancoelcapital.financialops.core.FinancialOperationRepository;
import com.bancoelcapital.financialops.core.FinancialOperationStatus;
import com.bancoelcapital.financialops.core.FinancialOperationType;
import com.bancoelcapital.financialops.core.Movement;
import com.bancoelcapital.financialops.core.MovementDirection;
import com.bancoelcapital.financialops.core.MovementRepository;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;

import jakarta.persistence.EntityExistsException;

/**
 * Transfer use case (Financial Operations module, ADR-02/ADR-04/ADR-05/ADR-18). Coordinates
 * authorization on the source holder, validation of both accounts, money-grade idempotency in a
 * dedicated namespace, immutable debit plus credit movement creation, and balance application
 * through the Accounts-owned contracts. Each confirmation runs in exactly one outer local
 * transaction; the winner reload after a uniqueness race runs in a separate fresh read transaction
 * (see below).
 *
 * <p>The idempotency key is consulted before deterministic rejections on both paths. Constructed
 * idempotency records are persist-only (never merged over an assigned key), and a same-key loser
 * reloads the committed winner in a fresh transaction before replay, conflict, or authorization
 * failure. An existing rejection is therefore immutable evidence, not a row to refresh.
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

  /**
   * Stable internal diagnostic for the H-02 anomaly. It intentionally carries no idempotency key,
   * operation id, actor, account, amount or hash: the event name alone identifies the failure.
   */
  private static final String CONFIRMED_OPERATION_MISSING =
      "transfer.recovery.confirmed_operation_missing";

  private static final Logger log = LoggerFactory.getLogger(TransferService.class);

  private final FinancialOperationRepository operations;
  private final MovementRepository movements;
  private final TransferOperationIdempotencyRepository idempotency;
  private final AccountLookupService lookup;
  private final AccountCreditService credits;
  private final AccountDebitService debits;
  private final AuthorizationService authorization;
  private final AuditRecorder audit;
  private final BeneficiaryResolver beneficiaries;
  private final TransferBeneficiaryUsageRepository usage;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final TransactionTemplate freshWriteTx;

  public TransferService(
      FinancialOperationRepository operations,
      MovementRepository movements,
      TransferOperationIdempotencyRepository idempotency,
      AccountLookupService lookup,
      AccountCreditService credits,
      AccountDebitService debits,
      AuthorizationService authorization,
      AuditRecorder audit,
      BeneficiaryResolver beneficiaries,
      TransferBeneficiaryUsageRepository usage,
      PlatformTransactionManager transactionManager) {
    this.operations = operations;
    this.movements = movements;
    this.idempotency = idempotency;
    this.lookup = lookup;
    this.credits = credits;
    this.debits = debits;
    this.authorization = authorization;
    this.audit = audit;
    this.beneficiaries = beneficiaries;
    this.usage = usage;
    this.writeTx = new TransactionTemplate(transactionManager);
    this.readTx = new TransactionTemplate(transactionManager);
    this.readTx.setReadOnly(true);
    // E5: the transfer write transaction pins READ COMMITTED so the beneficiary validation point
    // observes deletes committed before its second read. No other transaction is affected.
    this.writeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    // Recovery after a failed write must not reuse the caller's rollback-only/aborted transaction.
    // This template is intentionally used only by the catch paths, never for the main attempt or
    // for a key that already existed when transfer() was entered.
    this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.freshWriteTx = new TransactionTemplate(transactionManager);
    this.freshWriteTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public TransferResult transfer(
      TransferCommand command, String idempotencyKey, AuthenticatedActor actor) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new TransferOperationException(
          TransferOperationException.Kind.FAILED, "idempotency key is required");
    }
    // Authentication boundary: reject before hashing the intent or touching account,
    // idempotency, operation, audit or persistence state. This keeps known, unknown and
    // same-account sources indistinguishable to an unauthenticated caller.
    if (actor == null || actor.subject() == null || actor.subject().isBlank()) {
      throw new TransferOperationException(
          TransferOperationException.Kind.UNAUTHENTICATED, "unauthenticated actor");
    }
    // The key is consulted before any deterministic rejection on both paths. Existing-key
    // recovery performs its own authorization against persisted snapshots before comparing
    // intents, so this early lookup does not turn idempotency into a cross-actor oracle.
    String hash = requestHash(command);
    Optional<TransferOperationIdempotency> existing = idempotency.findById(idempotencyKey);
    if (existing.isPresent()) {
      return recoverExisting(existing.get(), command, hash, idempotencyKey, actor);
    }
    if (command.beneficiaryId() != null) {
      return transferViaBeneficiary(command, idempotencyKey, actor);
    }
    if (command.sourceAccountId() != null
        && command.sourceAccountId().equals(command.destinationAccountId())) {
      return rejectOrRecover(
          command, idempotencyKey, hash, actor, "source and destination must differ");
    }
    // Account identities are server-resolved: the client supplies only account UUIDs, never
    // holders, so authorization cannot be satisfied with a mismatched holder.
    // Account UUIDs are unguessable; unknown ids deterministically reject like the
    // established "unknown holder" precedent instead of leaking through auth paths.
    Optional<String> sourceHolder = lookup.findHolderCustomerId(command.sourceAccountId());
    if (sourceHolder.isEmpty()) {
      return rejectOrRecover(command, idempotencyKey, hash, actor, "unknown account");
    }
    Optional<String> destinationHolder =
        lookup.findHolderCustomerId(command.destinationAccountId());
    if (destinationHolder.isEmpty()) {
      return rejectOrRecover(command, idempotencyKey, hash, actor, "unknown account");
    }
    var decision = authorization.decideTransfer(actor, sourceHolder.get());
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    Optional<TransferOperationIdempotency> lateExisting = idempotency.findById(idempotencyKey);
    if (lateExisting.isPresent()) {
      return recoverExisting(lateExisting.get(), command, hash, idempotencyKey, actor);
    }
    return submit(command, idempotencyKey, hash, actor, null);
  }

  /**
   * Beneficiary-path transfer (ADR-21, P1a single-resolve slice). The client-supplied destination
   * is never used: the destination comes exclusively from the owner-scoped server-side resolution.
   * Both authorizations are required: source-holder transfer permission and beneficiary-owner
   * management permission. Missing and foreign beneficiaries are indistinguishable.
   *
   * <p>Race window (E5 deferred, NOT claimed here): the beneficiary is resolved once, outside the
   * write transaction and without a beneficiary row lock. A beneficiary deleted after this
   * resolution but before the transfer commits is not observed; the transfer may confirm on the
   * validated snapshot while the deletion stays effective for future uses. The second-read
   * validation point plus pinned READ COMMITTED plus barrier proof arrive in a later package.
   */
  private TransferResult transferViaBeneficiary(
      TransferCommand command, String idempotencyKey, AuthenticatedActor actor) {
    String hash = requestHash(command);
    Optional<String> sourceHolder = lookup.findHolderCustomerId(command.sourceAccountId());
    if (sourceHolder.isEmpty()) {
      return rejectOrRecover(command, idempotencyKey, hash, actor, "unknown account");
    }
    var transferDecision = authorization.decideTransfer(actor, sourceHolder.get());
    if (!transferDecision.allowed()) {
      throw forbidden(transferDecision.reason());
    }
    Optional<TransferOperationIdempotency> existing = idempotency.findById(idempotencyKey);
    if (existing.isPresent()) {
      return recoverExisting(existing.get(), command, hash, idempotencyKey, actor);
    }
    Optional<BeneficiaryResolution> resolved =
        beneficiaries.resolveForOwner(command.beneficiaryId(), actorSubject(actor));
    if (resolved.isEmpty()) {
      return rejectOrRecover(command, idempotencyKey, hash, actor, "invalid beneficiary");
    }
    var manageDecision =
        authorization.decideManageBeneficiary(actor, resolved.get().ownerCustomerId());
    if (!manageDecision.allowed()) {
      throw forbidden(manageDecision.reason());
    }
    if (command.sourceAccountId() != null
        && command.sourceAccountId().equals(resolved.get().destinationAccountId())) {
      return rejectOrRecover(
          command, idempotencyKey, hash, actor, "source and destination must differ");
    }
    TransferCommand effective =
        new TransferCommand(
            command.sourceAccountId(),
            resolved.get().destinationAccountId(),
            command.beneficiaryId(),
            command.amountMinorUnits(),
            command.currency());
    return submit(effective, idempotencyKey, hash, actor, resolved.get());
  }

  /**
   * Collision-aware deterministic rejection. The insert may lose a concurrent race for the same
   * key; the losing write transaction rolls back completely, then the committed winner is loaded in
   * a fresh read transaction and resolved through the normal authorization/intent checks. Without a
   * winner the collision is not a determinable idempotency outcome and propagates.
   */
  private TransferResult rejectOrRecover(
      TransferCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor,
      String reason) {
    try {
      return writeTx
          .execute(status -> reject(command, idempotencyKey, hash, actor, reason))
          .resultOrThrow();
    } catch (DataIntegrityViolationException | EntityExistsException collision) {
      return recoverWinnerInFreshReadTx(
          idempotencyKey, command, hash, idempotencyKey, actor, collision);
    }
  }

  /**
   * Reloads the committed winner and resolves it in one independent read-only transaction. This is
   * required after a failed write: the caller's transaction may already be rollback-only, and
   * PostgreSQL may have aborted it entirely after a uniqueness violation. Every decisive read
   * (operation, source holder, usage) therefore runs inside this fresh transaction, not outside it.
   */
  private TransferResult recoverWinnerInFreshReadTx(
      String winnerKey,
      TransferCommand command,
      String hash,
      String idempotencyKey,
      AuthenticatedActor actor,
      RuntimeException noWinner) {
    return readTx.execute(
        status ->
            idempotency
                .findById(winnerKey)
                .map(winner -> recoverExisting(winner, command, hash, idempotencyKey, actor))
                .orElseThrow(() -> noWinner));
  }

  /**
   * Shared submission tail for direct and beneficiary intents: post-lock idempotency re-check,
   * atomic persist, and winner reload after a uniqueness race. The command carries the effective
   * destination; beneficiary intent keeps its identity via requestHash and the P0.2 intent columns.
   * The resolution is null on the direct path; on the beneficiary path it supplies the usage
   * snapshots persisted atomically with the confirmation (P2).
   */
  private TransferResult submit(
      TransferCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor,
      BeneficiaryResolution beneficiary) {
    final AttemptOutcome outcome;
    try {
      outcome =
          writeTx.execute(status -> attempt(command, idempotencyKey, hash, actor, beneficiary));
    } catch (LegRejected rejected) {
      // The first leg applied and the second leg refused: our write transaction rolled back,
      // discarding the partial balance effect. Persist the deterministic rejection in a fresh
      // write transaction that touches no balances.
      final AttemptOutcome rejection;
      try {
        // The main attempt transaction has already rolled back (including its first leg). Persist
        // the terminal rejection in a fresh transaction so an outer, rollback-only caller cannot
        // prevent it from committing.
        rejection =
            freshWriteTx.execute(
                status -> reject(command, idempotencyKey, hash, actor, rejected.getReason()));
      } catch (DataIntegrityViolationException | EntityExistsException collision) {
        // A concurrent same-key winner committed between our re-check and this insert: our
        // transaction rolled back with nothing persisted. Resolve through the existing
        // recovery path instead of leaking a persistence exception — but only when the
        // winner actually exists. Without a winner there is no financial result to return,
        // so the original collision propagates untouched.
        return recoverWinnerInFreshReadTx(
            idempotencyKey, command, hash, idempotencyKey, actor, collision);
      }
      return rejection.resultOrThrow();
    } catch (DuplicateDetected | DataIntegrityViolationException | EntityExistsException race) {
      // Our own write transaction rolled back with nothing persisted: either a concurrent
      // same-key attempt committed while we waited on the account row locks (detected by the
      // post-lock re-check), or the database arbiter rejected our insert. Reload and resolve
      // the winner wholly in a fresh read transaction; what we read there is committed state
      // on every engine.
      return recoverWinnerInFreshReadTx(
          idempotencyKey,
          command,
          hash,
          idempotencyKey,
          actor,
          new TransferOperationException(
              TransferOperationException.Kind.UNKNOWN,
              "uncertain outcome, retry with the same idempotency key"));
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
    if (stored.isEmpty() || !matchesIntent(stored.get(), requestHash)) {
      return Optional.empty();
    }
    var record = stored.get();
    UUID operationId = record.getOperationId();
    Optional<UUID> sourceAccountId;
    if (operationId == null) {
      // No operation reference: never query the operation repository with a null id. The
      // persisted intent source is the only authoritative source left, so authorization still
      // runs against it before this row is reported as absent under the existing GET contract.
      sourceAccountId = Optional.ofNullable(record.getSourceAccountId());
    } else {
      Optional<FinancialOperation> operation = operations.findById(operationId);
      if (operation.isEmpty()) {
        return Optional.empty();
      }
      sourceAccountId = Optional.of(operation.get().getAccountId());
    }
    Optional<String> sourceHolder = sourceAccountId.flatMap(lookup::findHolderCustomerId);
    if (sourceHolder.isEmpty()) {
      return Optional.empty();
    }
    var queryDecision = authorization.decideTransfer(actor, sourceHolder.get());
    if (!queryDecision.allowed()) {
      throw forbidden(queryDecision.reason());
    }
    if (operationId == null) {
      // An inconsistent row has no reportable operation: absence maps to the existing 404,
      // never to a fabricated confirmed result.
      return Optional.empty();
    }
    return Optional.of(new TransferOutcome(operationId, record.getOutcome()));
  }

  /**
   * Runs inside exactly one outer local write transaction. Both Accounts-owned contracts join that
   * same transaction (REQUIRED propagation): the row locks are acquired in UUID ascending order and
   * held until commit. Returns normally (never throws business outcomes) so REJECTED records
   * actually commit; technical failures propagate and roll back.
   */
  private AttemptOutcome attempt(
      TransferCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor,
      BeneficiaryResolution beneficiary) {
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
    if (command.beneficiaryId() != null) {
      revalidateBeneficiary(command, beneficiary, idempotencyKey);
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
            idempotencyKey,
            operationId,
            hash,
            TransferOperationOutcome.CONFIRMED,
            command.sourceAccountId(),
            command.beneficiaryId(),
            command.beneficiaryId() != null ? "beneficiary-transfer" : "direct",
            command.amountMinorUnits(),
            command.currency()));
    if (command.beneficiaryId() != null) {
      usage.save(
          new TransferBeneficiaryUsage(
              operationId,
              command.beneficiaryId(),
              beneficiary.ownerCustomerId(),
              beneficiary.aliasSnapshot()));
    }
    String details =
        "source="
            + command.sourceAccountId()
            + " destination="
            + command.destinationAccountId()
            + " amount="
            + command.amountMinorUnits()
            + " currency="
            + command.currency();
    if (command.beneficiaryId() != null) {
      details +=
          " beneficiaryId="
              + command.beneficiaryId()
              + " beneficiaryOwner="
              + beneficiary.ownerCustomerId()
              + " beneficiaryAlias="
              + beneficiary.aliasSnapshot();
    }
    audit.record(
        new AuditEntry(
            actorSubject(actor),
            "TRANSFER_CONFIRMED",
            "FinancialOperation",
            operationId.toString(),
            "CONFIRMED",
            details));
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
            idempotencyKey,
            operationId,
            hash,
            TransferOperationOutcome.REJECTED,
            command.sourceAccountId(),
            command.beneficiaryId(),
            command.beneficiaryId() != null ? "beneficiary-transfer" : "direct",
            command.amountMinorUnits(),
            command.currency()));
    audit.record(
        new AuditEntry(
            actorSubject(actor),
            "TRANSFER_REJECTED",
            "FinancialOperation",
            idempotencyKey,
            "REJECTED",
            reason));
    // Surface a same-key uniqueness collision inside this transaction, before commit. The caller
    // then observes a fully rolled-back loser and can reload the committed winner separately.
    operations.flush();
    return new AttemptOutcome.Rejected(reason);
  }

  /**
   * Same key with incompatible payload. The conflicting audit is recorded in its own write
   * transaction and the conflict is thrown only afterwards, so the evidence survives; no money
   * moves and idempotency state is untouched. Never called from inside a read-only transaction.
   */
  private TransferResult conflict(String idempotencyKey, AuthenticatedActor actor) {
    freshWriteTx.execute(
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

  /**
   * Existing-key POST recovery (ADR-21:117, scope A1). Authorization runs against persisted
   * snapshots BEFORE identity comparison: source holder from the persisted operation (falling back
   * to the persisted intent source), plus the persisted beneficiary owner snapshot when the row
   * evidences an owned beneficiary transfer. Denial is indistinguishable (401/403) with zero writes
   * and reveals nothing about match versus conflict. The live beneficiary table is never consulted.
   * Historical rows and owner-absent invalid rejections need source-only authorization. A confirmed
   * beneficiary row without an owner snapshot fails closed.
   *
   * <p>This method runs in the caller's transaction. Existing-key recovery at the start of {@link
   * #transfer} deliberately keeps that transaction so read-your-writes is preserved. Only
   * post-failure callers wrap it inside {@code readTx} ({@code REQUIRES_NEW}); conflict evidence is
   * always written by {@code freshWriteTx}.
   */
  private TransferResult recoverExisting(
      TransferOperationIdempotency stored,
      TransferCommand command,
      String hash,
      String idempotencyKey,
      AuthenticatedActor actor) {
    authorizeReplay(stored, actor);
    if (!matchesIntent(stored, command, hash)) {
      return conflict(idempotencyKey, actor);
    }
    return replay(stored);
  }

  /**
   * Authorizes a replay against persisted snapshots. Ordering is deliberate: source and (when
   * evidenced) beneficiary-owner authorization complete first, so a foreign actor receives the
   * ordinary 403 and never learns that the persisted row is corrupt. Only then is the H-02 anomaly
   * surfaced, before intent comparison, so a mismatched payload also fails closed without a
   * conflict audit and a dangling reference is never replayed as success.
   *
   * <p>A confirmed beneficiary row without a usage snapshot remains unauthorized (403): its owner
   * cannot be proven, so the anomaly is not disclosed. On PostgreSQL such a row cannot reference a
   * missing operation because of the V8 foreign key.
   */
  private void authorizeReplay(TransferOperationIdempotency stored, AuthenticatedActor actor) {
    UUID operationId = stored.getOperationId();
    Optional<FinancialOperation> operation =
        operationId == null ? Optional.empty() : operations.findById(operationId);
    UUID source =
        operationId == null
            ? stored.getSourceAccountId()
            : operation.map(FinancialOperation::getAccountId).orElse(stored.getSourceAccountId());
    Optional<String> holder =
        source == null ? Optional.empty() : lookup.findHolderCustomerId(source);
    if (holder.isEmpty()) {
      throw forbidden("transfer source unavailable");
    }
    var transferDecision = authorization.decideTransfer(actor, holder.get());
    if (!transferDecision.allowed()) {
      throw forbidden(transferDecision.reason());
    }
    if ("beneficiary-transfer".equals(stored.getPath())) {
      Optional<TransferBeneficiaryUsage> use =
          operationId == null ? Optional.empty() : usage.findById(operationId);
      if (use.isEmpty()) {
        if (stored.getOutcome() == TransferOperationOutcome.CONFIRMED) {
          throw forbidden("beneficiary owner snapshot unavailable");
        }
      } else {
        var manageDecision =
            authorization.decideManageBeneficiary(actor, use.get().getBeneficiaryOwner());
        if (!manageDecision.allowed()) {
          throw forbidden(manageDecision.reason());
        }
      }
    }
    // H-02: a CONFIRMED row must reference an operation that can be resolved. This runs after all
    // applicable authorization and before matchesIntent, so denial still wins, a mismatch is not
    // reported as a conflict, and the anomaly never produces a successful replay or new effects.
    if (operationId != null
        && operation.isEmpty()
        && stored.getOutcome() == TransferOperationOutcome.CONFIRMED) {
      log.warn(CONFIRMED_OPERATION_MISSING);
      throw new TransferOperationException(
          TransferOperationException.Kind.FAILED,
          "stored confirmed transfer operation reference is unavailable");
    }
  }

  private TransferResult replay(TransferOperationIdempotency stored) {
    if (stored.getOutcome() == TransferOperationOutcome.REJECTED) {
      throw new TransferOperationException(
          TransferOperationException.Kind.REJECTED, "request previously rejected");
    }
    // A CONFIRMED record must reference the operation it confirms. Without that reference the
    // persisted evidence is inconsistent: returning success would hand the client a confirmed
    // transfer with no operation id, and inventing one would fabricate financial history.
    if (stored.getOperationId() == null) {
      throw new TransferOperationException(
          TransferOperationException.Kind.FAILED,
          "stored confirmed transfer has no operation reference");
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
    if (command.beneficiaryId() != null) {
      return sha256Hex(
          "beneficiary-transfer|"
              + String.valueOf(command.sourceAccountId()).toLowerCase(Locale.ROOT)
              + "|"
              + String.valueOf(command.beneficiaryId()).toLowerCase(Locale.ROOT)
              + "|"
              + String.valueOf(command.amountMinorUnits())
              + "|"
              + String.valueOf(command.currency()).toUpperCase(Locale.ROOT));
    }
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

  private static String sha256Hex(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new TransferOperationException(
          TransferOperationException.Kind.FAILED, "cannot hash request");
    }
  }

  /**
   * Intent comparison for an existing idempotency key (ADR-21:117, scope B). The stored hash must
   * match and every persisted identity field must agree with the incoming command: source, path
   * discriminator, beneficiary id on the beneficiary path, amount, and canonically compared
   * currency. Historical rows (null path, pre-V11) keep hash-only legacy semantics. The direct
   * destination has no persisted column, so it stays covered by the hash (documented limitation).
   */
  static boolean matchesIntent(
      TransferOperationIdempotency stored, TransferCommand command, String hash) {
    if (!stored.getRequestHash().equals(hash)) {
      return false;
    }
    if (stored.getPath() == null) {
      return true;
    }
    String path = command.beneficiaryId() != null ? "beneficiary-transfer" : "direct";
    if (!path.equals(stored.getPath())) {
      return false;
    }
    if (!Objects.equals(stored.getSourceAccountId(), command.sourceAccountId())) {
      return false;
    }
    if (!Objects.equals(stored.getAmountMinorUnits(), command.amountMinorUnits())) {
      return false;
    }
    if (!canonicalCurrency(stored.getCurrency()).equals(canonicalCurrency(command.currency()))) {
      return false;
    }
    if ("beneficiary-transfer".equals(path)) {
      return Objects.equals(stored.getBeneficiaryId(), command.beneficiaryId());
    }
    return true;
  }

  /**
   * GET recovery carries no request body, so only the stored hash can be compared. The hash is the
   * client-supplied integrity check here, not a substitute for unavailable fields.
   */
  static boolean matchesIntent(TransferOperationIdempotency stored, String hash) {
    return stored.getRequestHash().equals(hash);
  }

  private static String canonicalCurrency(String currency) {
    return String.valueOf(currency).toUpperCase(Locale.ROOT);
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
   * E5 validation point: owner-scoped beneficiary re-read after both account row locks are held,
   * adjacent to the post-lock idempotency re-check. Existence, owner, and resolved destination must
   * still agree with the entry snapshot; no beneficiary row lock is taken (ADR-18). Requires READ
   * COMMITTED statement-snapshot semantics to observe deletes committed before this read. Staleness
   * refuses through the winner-aware path so a concurrent same-key winner still replays instead of
   * persisting a competing rejection.
   */
  private void revalidateBeneficiary(
      TransferCommand command, BeneficiaryResolution beneficiary, String idempotencyKey) {
    Optional<BeneficiaryResolution> current =
        beneficiaries.resolveForOwner(command.beneficiaryId(), beneficiary.ownerCustomerId());
    if (current.isEmpty()
        || !current.get().ownerCustomerId().equals(beneficiary.ownerCustomerId())
        || !current.get().destinationAccountId().equals(command.destinationAccountId())) {
      throw secondLegRefusal(idempotencyKey, "invalid beneficiary");
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
