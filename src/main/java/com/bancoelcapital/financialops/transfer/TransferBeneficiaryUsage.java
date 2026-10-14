package com.bancoelcapital.financialops.transfer;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Beneficiary usage evidence for a CONFIRMED beneficiary transfer (ADR-21). Records which saved
 * beneficiary was used plus the owner/alias snapshots so the transfer stays reconstructible after
 * beneficiary deletion. Logical references only: no FK to beneficiaries or financial_operations.
 * One row per CONFIRMED beneficiary transfer; no row for direct transfers or rejections. The
 * resolved destination is derived from the linked CREDIT movement (ADR-18, Option A), never
 * duplicated here.
 */
@Entity
@Table(name = "transfer_beneficiary_usage")
public class TransferBeneficiaryUsage {

  @Id
  @Column(name = "operation_id")
  private UUID operationId;

  @Column(name = "beneficiary_id", nullable = false)
  private UUID beneficiaryId;

  @Column(name = "beneficiary_owner", nullable = false, length = 64)
  private String beneficiaryOwner;

  @Column(name = "beneficiary_alias", length = 64)
  private String beneficiaryAlias;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected TransferBeneficiaryUsage() {}

  public TransferBeneficiaryUsage(
      UUID operationId, UUID beneficiaryId, String beneficiaryOwner, String beneficiaryAlias) {
    if (operationId == null) {
      throw new IllegalArgumentException("operation is required");
    }
    if (beneficiaryId == null) {
      throw new IllegalArgumentException("beneficiary is required");
    }
    if (beneficiaryOwner == null || beneficiaryOwner.isBlank()) {
      throw new IllegalArgumentException("beneficiary owner is required");
    }
    this.operationId = operationId;
    this.beneficiaryId = beneficiaryId;
    this.beneficiaryOwner = beneficiaryOwner;
    this.beneficiaryAlias = beneficiaryAlias;
    this.createdAt = Instant.now();
  }

  public UUID getOperationId() {
    return operationId;
  }

  public UUID getBeneficiaryId() {
    return beneficiaryId;
  }

  public String getBeneficiaryOwner() {
    return beneficiaryOwner;
  }

  public String getBeneficiaryAlias() {
    return beneficiaryAlias;
  }
}
