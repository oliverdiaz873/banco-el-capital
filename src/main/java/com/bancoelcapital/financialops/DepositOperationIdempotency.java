package com.bancoelcapital.financialops;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotency record for deposit operations (ADR-06). Same logical intent plus same key MUST NOT
 * create a second financial effect. Same key plus incompatible payload is a conflict, resolved by
 * comparing the stored request hash. Each new key is a distinct monetary intent.
 */
@Entity
@Table(name = "deposit_operation_idempotency")
public class DepositOperationIdempotency {

  @Id
  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "operation_id")
  private UUID operationId;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private DepositOperationOutcome outcome;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected DepositOperationIdempotency() {}

  public DepositOperationIdempotency(
      String idempotencyKey,
      UUID operationId,
      String requestHash,
      DepositOperationOutcome outcome) {
    this.idempotencyKey = idempotencyKey;
    this.operationId = operationId;
    this.requestHash = requestHash;
    this.outcome = outcome;
    this.createdAt = Instant.now();
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

  public DepositOperationOutcome getOutcome() {
    return outcome;
  }
}
