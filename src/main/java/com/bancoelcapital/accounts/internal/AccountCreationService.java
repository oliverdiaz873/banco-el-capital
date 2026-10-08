package com.bancoelcapital.accounts.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.audit.api.AuditEntry;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;
import com.bancoelcapital.identity.IdentityGateway;

/**
 * Account creation use case (Accounts module, ADR-03). Single-owner MVP, ACTIVE on creation, single
 * validation of an explicit ISO currency at creation time; cross-account currency compatibility is
 * a transfer-time rule, not decided here.
 *
 * <p>One transaction covers account, idempotency record and audit evidence, so a timeout before
 * commit leaves nothing behind while a timeout after commit is replayable through the stored key. A
 * technical failure MUST NOT be auto-classified: callers query the key to determine the outcome.
 */
@Service
public class AccountCreationService {

  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Set<String> PRODUCTS = Set.of("BASIC");

  private final AccountRepository accounts;
  private final AccountCreationIdempotencyRepository idempotency;
  private final AuthorizationService authorization;
  private final IdentityGateway identity;
  private final AuditRecorder audit;

  public AccountCreationService(
      AccountRepository accounts,
      AccountCreationIdempotencyRepository idempotency,
      AuthorizationService authorization,
      IdentityGateway identity,
      AuditRecorder audit) {
    this.accounts = accounts;
    this.idempotency = idempotency;
    this.authorization = authorization;
    this.identity = identity;
    this.audit = audit;
  }

  @Transactional
  public AccountResult create(
      AccountCreationCommand command, String idempotencyKey, AuthenticatedActor actor) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new AccountCreationException(
          AccountCreationException.Kind.FAILED, "idempotency key is required");
    }
    var decision = authorization.decideCreateAccount(actor, command.holderCustomerId());
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    String hash = requestHash(command);
    Optional<AccountCreationIdempotency> existing = idempotency.findById(idempotencyKey);
    if (existing.isPresent()) {
      return replay(existing.get(), hash);
    }
    try {
      return attempt(command, idempotencyKey, hash, actor);
    } catch (DataIntegrityViolationException concurrent) {
      // Same-key concurrent winner committed first: reload and replay it.
      return replay(
          idempotency
              .findById(idempotencyKey)
              .orElseThrow(
                  () ->
                      new AccountCreationException(
                          AccountCreationException.Kind.FAILED,
                          "uncertain outcome, query the idempotency key")),
          hash);
    }
  }

  /**
   * Queryable outcome for timeout/disconnect recovery (ADR-04 determinability). The caller MUST be
   * authorized for the stored holder; otherwise no account id is revealed (403/401 via the shared
   * authorization rule).
   */
  @Transactional(readOnly = true)
  public Optional<AccountResult> findAuthorizedByKey(
      String idempotencyKey, String requestHash, AuthenticatedActor actor) {
    Optional<AccountCreationIdempotency> stored = idempotency.findById(idempotencyKey);
    if (stored.isEmpty() || !stored.get().getRequestHash().equals(requestHash)) {
      return Optional.empty();
    }
    var record = stored.get();
    var queryDecision = authorization.decideCreateAccount(actor, record.getHolderCustomerId());
    if (!queryDecision.allowed()) {
      throw forbidden(queryDecision.reason());
    }
    if (record.getOutcome() != CreationOutcome.CONFIRMED) {
      return Optional.empty();
    }
    return Optional.of(new AccountResult(record.getAccountId(), false));
  }

  @Transactional(readOnly = true)
  public Optional<CreationOutcome> outcomeOf(String idempotencyKey) {
    return idempotency.findById(idempotencyKey).map(AccountCreationIdempotency::getOutcome);
  }

  private AccountResult attempt(
      AccountCreationCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor) {
    String rejection = validate(command);
    if (rejection != null) {
      idempotency.save(
          new AccountCreationIdempotency(
              idempotencyKey, null, hash, CreationOutcome.REJECTED, command.holderCustomerId()));
      audit.record(
          new AuditEntry(
              actor.subject(),
              "ACCOUNT_CREATION_REJECTED",
              "Account",
              idempotencyKey,
              "REJECTED",
              rejection));
      throw new AccountCreationException(AccountCreationException.Kind.REJECTED, rejection);
    }
    Account account =
        new Account(
            UUID.randomUUID(),
            command.holderCustomerId(),
            command.currency(),
            command.productCode());
    accounts.save(account);
    idempotency.save(
        new AccountCreationIdempotency(
            idempotencyKey,
            account.getId(),
            hash,
            CreationOutcome.CONFIRMED,
            command.holderCustomerId()));
    audit.record(
        new AuditEntry(
            actor.subject(),
            "ACCOUNT_CREATED",
            "Account",
            account.getId().toString(),
            "CONFIRMED",
            "holder=" + command.holderCustomerId()));
    return new AccountResult(account.getId(), true);
  }

  private AccountResult replay(AccountCreationIdempotency stored, String hash) {
    if (!stored.getRequestHash().equals(hash)) {
      throw new AccountCreationException(
          AccountCreationException.Kind.CONFLICT,
          "idempotency key already used with incompatible payload");
    }
    if (stored.getOutcome() == CreationOutcome.REJECTED) {
      throw new AccountCreationException(
          AccountCreationException.Kind.REJECTED, "request previously rejected");
    }
    return new AccountResult(stored.getAccountId(), false);
  }

  private String validate(AccountCreationCommand command) {
    if (command.holderCustomerId() == null || command.holderCustomerId().isBlank()) {
      return "holder is required";
    }
    if (!identity.customerExists(command.holderCustomerId())) {
      return "unknown holder";
    }
    if (command.currency() == null || !CURRENCY.matcher(command.currency()).matches()) {
      return "currency must be an explicit ISO 4217 code";
    }
    if (command.productCode() == null || !PRODUCTS.contains(command.productCode())) {
      return "unsupported product";
    }
    return null;
  }

  private AccountCreationException forbidden(String reason) {
    if (reason != null && reason.contains("unauthenticated")) {
      return new AccountCreationException(AccountCreationException.Kind.UNAUTHENTICATED, reason);
    }
    return new AccountCreationException(AccountCreationException.Kind.FORBIDDEN, reason);
  }

  static String requestHash(AccountCreationCommand command) {
    try {
      String canonical =
          command.holderCustomerId() + "|" + command.currency() + "|" + command.productCode();
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new AccountCreationException(
          AccountCreationException.Kind.FAILED, "cannot hash request");
    }
  }
}
