package com.bancoelcapital.customers.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.audit.api.AuditEntry;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * Customer creation use case (Identity/Customers module, ADR-01/ADR-09). Client-provided logical
 * id, single transaction covering customer, idempotency record and audit evidence.
 */
@Service
public class CustomerCreationService {

  private static final Pattern CUSTOMER_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  private final CustomerRepository customers;
  private final CustomerCreationIdempotencyRepository idempotency;
  private final AuthorizationService authorization;
  private final AuditRecorder audit;

  public CustomerCreationService(
      CustomerRepository customers,
      CustomerCreationIdempotencyRepository idempotency,
      AuthorizationService authorization,
      AuditRecorder audit) {
    this.customers = customers;
    this.idempotency = idempotency;
    this.authorization = authorization;
    this.audit = audit;
  }

  @Transactional
  public CustomerResult create(
      CustomerCreationCommand command, String idempotencyKey, AuthenticatedActor actor) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new CustomerCreationException(
          CustomerCreationException.Kind.FAILED, "idempotency key is required");
    }
    var decision = authorization.decideCreateCustomer(actor, command.customerId());
    if (!decision.allowed()) {
      throw forbidden(decision.reason());
    }
    String hash = requestHash(command);
    Optional<CustomerCreationIdempotency> existing = idempotency.findById(idempotencyKey);
    if (existing.isPresent()) {
      return replay(existing.get(), hash);
    }
    try {
      return attempt(command, idempotencyKey, hash, actor);
    } catch (DataIntegrityViolationException concurrent) {
      Optional<CustomerCreationIdempotency> winner = idempotency.findById(idempotencyKey);
      if (winner.isPresent()) {
        return replay(winner.get(), hash);
      }
      // Same customerId raced with a different key: resolve deterministically by business key.
      Optional<Customer> raced = customers.findById(command.customerId());
      if (raced.isPresent()) {
        return resolveDuplicate(command, idempotencyKey, hash, actor, raced.get());
      }
      throw new CustomerCreationException(
          CustomerCreationException.Kind.FAILED, "uncertain outcome, query the idempotency key");
    }
  }

  @Transactional(readOnly = true)
  public Optional<CustomerCreationOutcome> outcomeOf(String idempotencyKey) {
    return idempotency.findById(idempotencyKey).map(CustomerCreationIdempotency::getOutcome);
  }

  private CustomerResult attempt(
      CustomerCreationCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor) {
    String rejection = validate(command);
    if (rejection != null) {
      idempotency.save(
          new CustomerCreationIdempotency(
              idempotencyKey, null, hash, CustomerCreationOutcome.REJECTED));
      audit.record(
          new AuditEntry(
              actor.subject(),
              "CUSTOMER_CREATION_REJECTED",
              "Customer",
              idempotencyKey,
              "REJECTED",
              rejection));
      throw new CustomerCreationException(CustomerCreationException.Kind.REJECTED, rejection);
    }
    Optional<Customer> duplicate = customers.findById(command.customerId());
    if (duplicate.isPresent()) {
      return resolveDuplicate(command, idempotencyKey, hash, actor, duplicate.get());
    }
    String normalizedDisplay = normalizedDisplayName(command.displayName());
    Customer customer = new Customer(command.customerId(), normalizedDisplay, actor.subject());
    customers.save(customer);
    idempotency.save(
        new CustomerCreationIdempotency(
            idempotencyKey, customer.getCustomerId(), hash, CustomerCreationOutcome.CONFIRMED));
    audit.record(
        new AuditEntry(
            actor.subject(),
            "CUSTOMER_CREATED",
            "Customer",
            customer.getCustomerId(),
            "CONFIRMED",
            "customer=" + customer.getCustomerId()));
    return new CustomerResult(customer.getCustomerId(), true);
  }

  private CustomerResult resolveDuplicate(
      CustomerCreationCommand command,
      String idempotencyKey,
      String hash,
      AuthenticatedActor actor,
      Customer existing) {
    String requestedDisplay = normalizedDisplayName(command.displayName());
    String storedDisplay = existing.getDisplayName();
    boolean samePayload =
        (requestedDisplay == null && storedDisplay == null)
            || (requestedDisplay != null && requestedDisplay.equals(storedDisplay));
    if (!samePayload) {
      audit.record(
          new AuditEntry(
              actor.subject(),
              "CUSTOMER_CREATION_CONFLICT",
              "Customer",
              idempotencyKey,
              "CONFLICT",
              "customer already exists with different payload"));
      throw new CustomerCreationException(
          CustomerCreationException.Kind.CONFLICT, "customer already exists");
    }
    idempotency.save(
        new CustomerCreationIdempotency(
            idempotencyKey, existing.getCustomerId(), hash, CustomerCreationOutcome.CONFIRMED));
    audit.record(
        new AuditEntry(
            actor.subject(),
            "CUSTOMER_CREATION_REUSED",
            "Customer",
            existing.getCustomerId(),
            "CONFIRMED",
            "customer=" + existing.getCustomerId()));
    return new CustomerResult(existing.getCustomerId(), false);
  }

  private CustomerResult replay(CustomerCreationIdempotency stored, String hash) {
    if (!stored.getRequestHash().equals(hash)) {
      throw new CustomerCreationException(
          CustomerCreationException.Kind.CONFLICT,
          "idempotency key already used with incompatible payload");
    }
    if (stored.getOutcome() == CustomerCreationOutcome.REJECTED) {
      throw new CustomerCreationException(
          CustomerCreationException.Kind.REJECTED, "request previously rejected");
    }
    return new CustomerResult(stored.getCustomerId(), false);
  }

  private String validate(CustomerCreationCommand command) {
    if (command.customerId() == null || !CUSTOMER_ID.matcher(command.customerId()).matches()) {
      return "customer id must match [A-Za-z0-9._-]{1,64}";
    }
    if (command.displayName() != null) {
      if (command.displayName().length() > 128) {
        return "display name must be at most 128 characters";
      }
      if (command.displayName().trim().isEmpty()) {
        return "display name must not be blank";
      }
    }
    return null;
  }

  private CustomerCreationException forbidden(String reason) {
    if (reason != null && reason.contains("unauthenticated")) {
      return new CustomerCreationException(CustomerCreationException.Kind.UNAUTHENTICATED, reason);
    }
    return new CustomerCreationException(CustomerCreationException.Kind.FORBIDDEN, reason);
  }

  static String requestHash(CustomerCreationCommand command) {
    try {
      String normalized = command.displayName() == null ? "" : command.displayName().trim();
      String canonical = command.customerId() + "|" + normalized;
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new CustomerCreationException(
          CustomerCreationException.Kind.FAILED, "cannot hash request");
    }
  }

  private static String normalizedDisplayName(String displayName) {
    if (displayName == null) {
      return null;
    }
    String trimmed = displayName.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
