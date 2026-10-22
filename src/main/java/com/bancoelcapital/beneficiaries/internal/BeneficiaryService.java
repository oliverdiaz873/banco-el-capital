package com.bancoelcapital.beneficiaries.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bancoelcapital.accounts.api.AccountDestinationLookup;
import com.bancoelcapital.accounts.api.DestinationCurrency;
import com.bancoelcapital.audit.api.AuditEntry;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;
import com.bancoelcapital.identity.IdentityGateway;

/**
 * Beneficiary use cases (Beneficiaries module, ADR-20). Owner-scoped saved destination references;
 * saving moves no money and removal deletes no account. Creation follows the project idempotency
 * discipline (ADR-06) in a dedicated namespace: same intent plus same key MUST NOT create a second
 * beneficiary, and deterministic business rejections persist as REJECTED idempotency rows with a
 * null beneficiary reference.
 *
 * <p>Each creation runs in exactly one outer local write transaction; the winner reload after a
 * uniqueness race runs in a separate fresh read transaction (Deposit/Transfer/Transition pattern:
 * PostgreSQL aborts the whole transaction on any constraint violation, so a reload issued inside
 * the same transaction after a DataIntegrityViolationException could never succeed there).
 *
 * <p>Authorization failures and missing keys persist nothing. UNKNOWN is caller-side uncertainty,
 * never a persisted state.
 */
@Service
public class BeneficiaryService {

  private static final int ALIAS_MAX_LENGTH = 64;

  private final BeneficiaryRepository beneficiaries;
  private final BeneficiaryCreationIdempotencyRepository idempotency;
  private final AccountDestinationLookup destinations;
  private final AuthorizationService authorization;
  private final IdentityGateway identity;
  private final AuditRecorder audit;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  public BeneficiaryService(
      BeneficiaryRepository beneficiaries,
      BeneficiaryCreationIdempotencyRepository idempotency,
      AccountDestinationLookup destinations,
      AuthorizationService authorization,
      IdentityGateway identity,
      AuditRecorder audit,
      PlatformTransactionManager transactionManager) {
    this.beneficiaries = beneficiaries;
    this.idempotency = idempotency;
    this.destinations = destinations;
    this.authorization = authorization;
    this.identity = identity;
    this.audit = audit;
    this.writeTx = new TransactionTemplate(transactionManager);
    this.readTx = new TransactionTemplate(transactionManager);
    this.readTx.setReadOnly(true);
  }

  public BeneficiaryResult create(
      BeneficiaryCommand command, String idempotencyKey, AuthenticatedActor actor) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new BeneficiaryException(
          BeneficiaryException.Kind.FAILED, "idempotency key is required");
    }
    // The owner is always server-resolved from the actor: the request carries no owner
    // identity, so authorization cannot be satisfied with a mismatched owner.
    String owner = actor == null ? null : actor.subject();
    var decision = authorization.decideManageBeneficiary(actor, owner);
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    String hash = requestHash(owner, command);
    Optional<BeneficiaryCreationIdempotency> existing = idempotency.findById(idempotencyKey);
    if (existing.isPresent()) {
      if (!existing.get().getRequestHash().equals(hash)) {
        return conflict(idempotencyKey, actor);
      }
      return replay(existing.get());
    }
    final AttemptOutcome outcome;
    try {
      outcome = writeTx.execute(status -> attempt(owner, command, idempotencyKey, hash, actor));
    } catch (DuplicateDetected | DataIntegrityViolationException race) {
      // Our own write transaction rolled back with nothing persisted: either a concurrent
      // same-key attempt committed while we ran, or the database arbiter rejected our insert
      // (same-key race or duplicate owner/destination under another key). Reload the
      // idempotency winner in a fresh read transaction; what we read there is committed
      // state on every engine.
      Optional<BeneficiaryCreationIdempotency> winner =
          readTx.execute(status -> idempotency.findById(idempotencyKey));
      if (winner.isPresent()) {
        if (!winner.get().getRequestHash().equals(hash)) {
          return conflict(idempotencyKey, actor);
        }
        return replay(winner.get());
      }
      // No same-key winner: the arbiter rejected a duplicate (owner, destination) saved
      // under a different key. Each key is a distinct intent, so this attempt rejects
      // deterministically under its own key in a fresh write transaction.
      if (readTx.execute(
          status ->
              beneficiaries.existsByOwnerCustomerIdAndDestinationAccountId(
                  owner, command.destinationAccountId()))) {
        return writeTx
            .execute(
                status ->
                    reject(
                        owner,
                        command,
                        idempotencyKey,
                        hash,
                        actor,
                        "duplicate destination for owner"))
            .resultOrThrow();
      }
      // Nothing determined: the winner may still be in flight. The caller is uncertain,
      // not failed; recovery reuses the same key.
      throw new BeneficiaryException(
          BeneficiaryException.Kind.UNKNOWN,
          "uncertain outcome, retry with the same idempotency key");
    }
    return outcome.resultOrThrow();
  }

  /**
   * Queryable outcome for timeout/disconnect recovery. Never re-executes the creation and never
   * mutates state. The caller MUST be authorized for the stored owner; hash mismatch and absence
   * both resolve to empty without leaking which one occurred.
   */
  @Transactional(readOnly = true)
  public Optional<BeneficiaryOutcome> findAuthorizedByKey(
      String idempotencyKey, String requestHash, AuthenticatedActor actor) {
    Optional<BeneficiaryCreationIdempotency> stored = idempotency.findById(idempotencyKey);
    if (stored.isEmpty() || !stored.get().getRequestHash().equals(requestHash)) {
      return Optional.empty();
    }
    var record = stored.get();
    var queryDecision = authorization.decideManageBeneficiary(actor, record.getOwnerCustomerId());
    if (!queryDecision.allowed()) {
      throw forbidden(queryDecision.reason());
    }
    return Optional.of(new BeneficiaryOutcome(record.getBeneficiaryId(), record.getOutcome()));
  }

  @Transactional(readOnly = true)
  public Optional<BeneficiaryCreationOutcome> outcomeOf(String idempotencyKey) {
    return idempotency.findById(idempotencyKey).map(BeneficiaryCreationIdempotency::getOutcome);
  }

  @Transactional(readOnly = true)
  public List<BeneficiaryView> listForOwner(AuthenticatedActor actor) {
    String owner = actor == null ? null : actor.subject();
    var decision = authorization.decideManageBeneficiary(actor, owner);
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    return beneficiaries.findByOwnerCustomerIdOrderByCreatedAtAsc(owner).stream()
        .map(BeneficiaryService::viewOf)
        .toList();
  }

  /**
   * Owner-scoped read: a missing row and another owner's row both resolve to empty, so callers
   * cannot distinguish them.
   */
  @Transactional(readOnly = true)
  public Optional<BeneficiaryView> findForOwner(UUID beneficiaryId, AuthenticatedActor actor) {
    String owner = actor == null ? null : actor.subject();
    var decision = authorization.decideManageBeneficiary(actor, owner);
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    return beneficiaries.findViewAndOwner(beneficiaryId, owner);
  }

  /**
   * Physical removal plus removal audit in one local transaction (ADR-20, precision B/C). Returns
   * false for a missing row or another owner's row (both map to 404); has no effect on accounts,
   * movements, operations, or any idempotency namespace.
   */
  public boolean remove(UUID beneficiaryId, AuthenticatedActor actor) {
    String owner = actor == null ? null : actor.subject();
    var decision = authorization.decideManageBeneficiary(actor, owner);
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    return writeTx.execute(
        status -> {
          Optional<Beneficiary> stored =
              beneficiaries.findByIdAndOwnerCustomerId(beneficiaryId, owner);
          if (stored.isEmpty()) {
            return false;
          }
          beneficiaries.delete(stored.get());
          audit.record(
              new AuditEntry(
                  actor.subject(),
                  "BENEFICIARY_REMOVED",
                  "Beneficiary",
                  beneficiaryId.toString(),
                  "CONFIRMED",
                  "owner=" + owner));
          return true;
        });
  }

  /**
   * Runs inside exactly one outer local write transaction. Returns normally (never throws business
   * outcomes) so REJECTED records actually commit; technical failures propagate and roll back.
   */
  private AttemptOutcome attempt(
      String owner,
      BeneficiaryCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor) {
    String alias = normalizeAlias(command.alias());
    if (alias == null) {
      return reject(owner, command, idempotencyKey, hash, actor, "alias is required");
    }
    if (command.destinationAccountId() == null) {
      return reject(owner, command, idempotencyKey, hash, actor, "destination is required");
    }
    if (!identity.customerExists(owner)) {
      return reject(owner, command, idempotencyKey, hash, actor, "unknown owner");
    }
    Optional<DestinationCurrency> destination =
        destinations.findDestination(command.destinationAccountId());
    if (destination.isEmpty()) {
      return reject(owner, command, idempotencyKey, hash, actor, "unknown destination");
    }
    // Same-key concurrent winner may have committed before this write transaction began;
    // replay it instead of writing a second beneficiary for one intent.
    if (idempotency.findById(idempotencyKey).isPresent()) {
      throw new DuplicateDetected();
    }
    UUID beneficiaryId = UUID.randomUUID();
    beneficiaries.save(
        new Beneficiary(
            beneficiaryId,
            owner,
            command.destinationAccountId(),
            alias,
            destination.get().currency()));
    idempotency.save(
        new BeneficiaryCreationIdempotency(
            idempotencyKey, beneficiaryId, hash, BeneficiaryCreationOutcome.CONFIRMED, owner));
    audit.record(
        new AuditEntry(
            actor.subject(),
            "BENEFICIARY_CREATED",
            "Beneficiary",
            beneficiaryId.toString(),
            "CONFIRMED",
            "owner=" + owner));
    // Explicit flush surfaces uniqueness violations deterministically inside this transaction
    // on both H2 and PostgreSQL (same SQL constraint), instead of deferring them to commit
    // time where the catch could no longer reload and replay the winner.
    beneficiaries.flush();
    return new AttemptOutcome.Confirmed(new BeneficiaryResult(beneficiaryId, true));
  }

  private AttemptOutcome reject(
      String owner,
      BeneficiaryCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor,
      String reason) {
    idempotency.save(
        new BeneficiaryCreationIdempotency(
            idempotencyKey, null, hash, BeneficiaryCreationOutcome.REJECTED, owner));
    audit.record(
        new AuditEntry(
            actor.subject(),
            "BENEFICIARY_REJECTED",
            "Beneficiary",
            idempotencyKey,
            "REJECTED",
            reason));
    // Explicit flush surfaces a same-key race deterministically inside this transaction.
    idempotency.flush();
    return new AttemptOutcome.Rejected(reason);
  }

  /**
   * Same key with incompatible payload. The conflicting audit is recorded in its own write
   * transaction and the conflict is thrown only afterwards, so the evidence survives; no
   * beneficiary is created and idempotency state is untouched. Never called from inside a read-only
   * transaction.
   */
  private BeneficiaryResult conflict(String idempotencyKey, AuthenticatedActor actor) {
    writeTx.execute(
        status -> {
          audit.record(
              new AuditEntry(
                  actor.subject(),
                  "BENEFICIARY_CONFLICT",
                  "Beneficiary",
                  idempotencyKey,
                  "CONFLICT",
                  "idempotency key already used with incompatible payload"));
          return null;
        });
    throw new BeneficiaryException(
        BeneficiaryException.Kind.CONFLICT,
        "idempotency key already used with incompatible payload");
  }

  private BeneficiaryResult replay(BeneficiaryCreationIdempotency stored) {
    if (stored.getOutcome() == BeneficiaryCreationOutcome.REJECTED) {
      throw new BeneficiaryException(
          BeneficiaryException.Kind.REJECTED, "request previously rejected");
    }
    return new BeneficiaryResult(stored.getBeneficiaryId(), false);
  }

  /**
   * Single normalization source of truth: trims surrounding whitespace, keeps inner spacing and
   * case, enforces length after trim, rejects control characters. Returns null when invalid;
   * callers persist the deterministic REJECTED outcome for invalid values.
   */
  static String normalizeAlias(String alias) {
    if (alias == null) {
      return null;
    }
    String normalized = alias.trim();
    if (normalized.isEmpty() || normalized.length() > ALIAS_MAX_LENGTH) {
      return null;
    }
    for (int i = 0; i < normalized.length(); i++) {
      if (Character.isISOControl(normalized.charAt(i))) {
        return null;
      }
    }
    return normalized;
  }

  private static BeneficiaryView viewOf(Beneficiary beneficiary) {
    return new BeneficiaryView(
        beneficiary.getId(),
        beneficiary.getAlias(),
        beneficiary.getDestinationAccountId(),
        beneficiary.getDestinationCurrency(),
        beneficiary.getCreatedAt());
  }

  private BeneficiaryException forbidden(String reason) {
    if (reason != null && reason.contains("unauthenticated")) {
      return new BeneficiaryException(BeneficiaryException.Kind.UNAUTHENTICATED, reason);
    }
    return new BeneficiaryException(BeneficiaryException.Kind.FORBIDDEN, reason);
  }

  /**
   * Canonical hash input (exact order, {@code |} separator, no whitespace): {@code
   * owner-lowercase|destination-uuid-lowercase|alias-normalized-verbatim}, UTF-8 bytes, SHA-256 hex
   * lowercase (64 chars). The alias leg uses the normalized (trimmed, case-preserved) value so
   * surrounding whitespace replays while case-distinct aliases hash distinctly. A null alias hashes
   * as empty so invalid-alias rejections stay deterministic per key.
   */
  static String requestHash(String owner, BeneficiaryCommand command) {
    try {
      String normalized = normalizeAlias(command.alias());
      String canonical =
          String.valueOf(owner).toLowerCase(Locale.ROOT)
              + "|"
              + String.valueOf(command.destinationAccountId()).toLowerCase(Locale.ROOT)
              + "|"
              + (normalized == null ? "" : normalized);
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new BeneficiaryException(BeneficiaryException.Kind.FAILED, "cannot hash request");
    }
  }

  /**
   * Outcome carrier: business rejections are thrown only after the write transaction commits, so
   * REJECTED idempotency records actually persist instead of being rolled back with the very
   * transaction that wrote them.
   */
  private sealed interface AttemptOutcome
      permits AttemptOutcome.Confirmed, AttemptOutcome.Rejected {

    record Confirmed(BeneficiaryResult result) implements AttemptOutcome {}

    record Rejected(String reason) implements AttemptOutcome {}

    default BeneficiaryResult resultOrThrow() {
      if (this instanceof Confirmed confirmed) {
        return confirmed.result();
      }
      throw new BeneficiaryException(
          BeneficiaryException.Kind.REJECTED, ((Rejected) this).reason());
    }
  }

  /** Control flow: a committed same-key record exists, so this attempt must not write anything. */
  private static final class DuplicateDetected extends RuntimeException {}
}
