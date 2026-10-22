package com.bancoelcapital.financialops.transfer;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Idempotency record for transfer operations (ADR-06, ADR-18, ADR-21). Dedicated namespace,
 * separate from deposit and withdrawal idempotency: same logical intent plus same key MUST NOT
 * create a second financial effect. Same key plus incompatible payload is a conflict, resolved by
 * comparing the stored request hash. Each new key is a distinct monetary intent.
 *
 * <p>Intent columns (source, beneficiary-or-absent, direct destination, path, amount, currency)
 * persist the declared intent for CONFIRMED and REJECTED outcomes so a repeated key can be proven
 * same-or-different without consulting live state. Rows predating V11 keep all intent columns NULL
 * (historical/legacy branch); legacy rows exist only as data, never through construction. A direct
 * destination is persisted only for direct transfers; beneficiary transfers keep it NULL because
 * their destination is server-resolved execution evidence. The database CHECK constraints below
 * mirror V11-V13 and remain the final arbiter. A CONFIRMED row always references its operation: V12
 * rejects the combination at the database and construction rejects it in Java.
 */
@Entity
@Table(name = "transfer_operation_idempotency")
@Checks({
  @Check(
      constraints =
          "(source_account_id is null and beneficiary_id is null and path is null"
              + " and amount_minor_units is null and currency is null) or (source_account_id is not"
              + " null and path is not null and amount_minor_units is not null and currency is not"
              + " null)"),
  @Check(constraints = "(path is null or path in ('direct','beneficiary-transfer'))"),
  @Check(
      constraints =
          "(path is null or (path = 'direct' and beneficiary_id is null)"
              + " or (path = 'beneficiary-transfer' and beneficiary_id is not null))"),
  @Check(constraints = "(destination_account_id is null or path is not distinct from 'direct')"),
  @Check(constraints = "(path is distinct from 'direct' or destination_account_id is not null)"),
  @Check(constraints = "(outcome <> 'CONFIRMED' or operation_id is not null)")
})
public class TransferOperationIdempotency implements Persistable<String> {

  @Id
  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  /**
   * Spring Data must call {@code persist}, never {@code merge}, for a newly constructed record. An
   * assigned identifier alone makes the default entity information consider the entity non-new,
   * which lets {@code save()} overwrite the stored row. The flag is true only for
   * constructor-created instances and flips false after load/persist.
   */
  @Transient private boolean isNew = true;

  @Column(name = "operation_id")
  private UUID operationId;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private TransferOperationOutcome outcome;

  @Column(name = "source_account_id")
  private UUID sourceAccountId;

  @Column(name = "beneficiary_id")
  private UUID beneficiaryId;

  /**
   * Client-supplied destination of a direct transfer, persisted for CONFIRMED and REJECTED
   * outcomes. NULL on beneficiary transfers (server-resolved destination) and on legacy rows that
   * predate V13.
   */
  @Column(name = "destination_account_id")
  private UUID destinationAccountId;

  @Column(name = "path", length = 32)
  private String path;

  @Column(name = "amount_minor_units")
  private Long amountMinorUnits;

  @Column(name = "currency", length = 3)
  private String currency;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected TransferOperationIdempotency() {}

  public TransferOperationIdempotency(
      String idempotencyKey,
      UUID operationId,
      String requestHash,
      TransferOperationOutcome outcome,
      UUID sourceAccountId,
      UUID beneficiaryId,
      UUID destinationAccountId,
      String path,
      Long amountMinorUnits,
      String currency) {
    if (path == null
        && (sourceAccountId != null
            || beneficiaryId != null
            || destinationAccountId != null
            || amountMinorUnits != null
            || currency != null)) {
      throw new IllegalArgumentException("historical compatibility requires all intent null");
    }
    if (path != null && (sourceAccountId == null || amountMinorUnits == null || currency == null)) {
      throw new IllegalArgumentException("new intent requires source, amount, and currency");
    }
    if (path != null && !path.equals("direct") && !path.equals("beneficiary-transfer")) {
      throw new IllegalArgumentException("unknown intent path");
    }
    if (path != null
        && ((path.equals("direct") && (beneficiaryId != null || destinationAccountId == null))
            || (path.equals("beneficiary-transfer")
                && (beneficiaryId == null || destinationAccountId != null)))) {
      throw new IllegalArgumentException("beneficiary and destination must match the intent path");
    }
    if (outcome == TransferOperationOutcome.CONFIRMED && operationId == null) {
      throw new IllegalArgumentException("confirmed outcome requires an operation reference");
    }
    this.idempotencyKey = idempotencyKey;
    this.operationId = operationId;
    this.requestHash = requestHash;
    this.outcome = outcome;
    this.sourceAccountId = sourceAccountId;
    this.beneficiaryId = beneficiaryId;
    this.destinationAccountId = destinationAccountId;
    this.path = path;
    this.amountMinorUnits = amountMinorUnits;
    this.currency = currency;
    this.createdAt = Instant.now();
  }

  @Override
  public String getId() {
    return idempotencyKey;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNew = false;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getOperationId() {
    return operationId;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public TransferOperationOutcome getOutcome() {
    return outcome;
  }

  public UUID getSourceAccountId() {
    return sourceAccountId;
  }

  public UUID getBeneficiaryId() {
    return beneficiaryId;
  }

  public UUID getDestinationAccountId() {
    return destinationAccountId;
  }

  public String getPath() {
    return path;
  }

  public Long getAmountMinorUnits() {
    return amountMinorUnits;
  }

  public String getCurrency() {
    return currency;
  }
}
