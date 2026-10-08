package com.bancoelcapital.financialops.core;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Financial operation: intent plus lifecycle (ADR-02/ADR-04). Owned by Financial Operations. The
 * referenced account is a logical cross-module reference: no physical FK to accounts. Money uses
 * integer minor units; currency is always explicit.
 */
@Entity
@Table(name = "financial_operations")
public class FinancialOperation {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation_type", nullable = false, length = 32)
  private FinancialOperationType operationType;

  @Column(nullable = false, length = 128)
  private String actor;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "amount_minor_units", nullable = false)
  private Long amountMinorUnits;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private FinancialOperationStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected FinancialOperation() {}

  public FinancialOperation(
      UUID id,
      FinancialOperationType operationType,
      String actor,
      UUID accountId,
      Long amountMinorUnits,
      String currency,
      FinancialOperationStatus status) {
    this.id = id;
    this.operationType = operationType;
    this.actor = actor;
    this.accountId = accountId;
    this.amountMinorUnits = amountMinorUnits;
    this.currency = currency;
    this.status = status;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public FinancialOperationType getOperationType() {
    return operationType;
  }

  public String getActor() {
    return actor;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public Long getAmountMinorUnits() {
    return amountMinorUnits;
  }

  public String getCurrency() {
    return currency;
  }

  public FinancialOperationStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
