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
 * Movement: confirmed immutable financial effect (ADR-02). Created only when its operation
 * confirms; never updated or deleted. The account is a logical cross-module reference.
 */
@Entity
@Table(name = "movements")
public class Movement {

  @Id private UUID id;

  @Column(name = "operation_id", nullable = false)
  private UUID operationId;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 6)
  private MovementDirection direction;

  @Column(name = "amount_minor_units", nullable = false)
  private Long amountMinorUnits;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Movement() {}

  public Movement(
      UUID id,
      UUID operationId,
      UUID accountId,
      MovementDirection direction,
      Long amountMinorUnits,
      String currency) {
    this.id = id;
    this.operationId = operationId;
    this.accountId = accountId;
    this.direction = direction;
    this.amountMinorUnits = amountMinorUnits;
    this.currency = currency;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getOperationId() {
    return operationId;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public MovementDirection getDirection() {
    return direction;
  }

  public Long getAmountMinorUnits() {
    return amountMinorUnits;
  }

  public String getCurrency() {
    return currency;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
