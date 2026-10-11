package com.bancoelcapital.beneficiaries.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotency record for beneficiary creation (ADR-06, ADR-20). Dedicated namespace, separate from
 * every other creation/operation namespace: same intent plus same key MUST NOT create a second
 * beneficiary. Same key plus incompatible payload is a conflict. Each new key is a distinct intent,
 * including a duplicate destination under a different key (which rejects deterministically under
 * its own key). REJECTED rows carry a null beneficiary reference.
 */
@Entity
@Table(name = "beneficiary_creation_idempotency")
public class BeneficiaryCreationIdempotency {

  @Id
  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "beneficiary_id")
  private UUID beneficiaryId;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private BeneficiaryCreationOutcome outcome;

  @Column(name = "owner_customer_id", nullable = false, length = 64)
  private String ownerCustomerId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected BeneficiaryCreationIdempotency() {}

  public BeneficiaryCreationIdempotency(
      String idempotencyKey,
      UUID beneficiaryId,
      String requestHash,
      BeneficiaryCreationOutcome outcome,
      String ownerCustomerId) {
    this.idempotencyKey = idempotencyKey;
    this.beneficiaryId = beneficiaryId;
    this.requestHash = requestHash;
    this.outcome = outcome;
    this.ownerCustomerId = ownerCustomerId;
    this.createdAt = Instant.now();
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getBeneficiaryId() {
    return beneficiaryId;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public BeneficiaryCreationOutcome getOutcome() {
    return outcome;
  }

  public String getOwnerCustomerId() {
    return ownerCustomerId;
  }
}
