package com.bancoelcapital.beneficiaries.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Beneficiary aggregate root (Beneficiaries module, ADR-20). A saved destination reference owned by
 * one customer: it MUST NOT own accounts, balances, movements, or financial logic. The owner and
 * destination are logical cross-module references: no physical FK to customers or accounts.
 */
@Entity
@Table(
    name = "beneficiaries",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_beneficiary_owner_destination",
            columnNames = {"owner_customer_id", "destination_account_id"}))
public class Beneficiary {

  @Id private UUID id;

  @Column(name = "owner_customer_id", nullable = false, length = 64)
  private String ownerCustomerId;

  @Column(name = "destination_account_id", nullable = false)
  private UUID destinationAccountId;

  @Column(nullable = false, length = 64)
  private String alias;

  @Column(name = "destination_currency", nullable = false, length = 3)
  private String destinationCurrency;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Beneficiary() {}

  public Beneficiary(
      UUID id,
      String ownerCustomerId,
      UUID destinationAccountId,
      String alias,
      String destinationCurrency) {
    this.id = id;
    this.ownerCustomerId = ownerCustomerId;
    this.destinationAccountId = destinationAccountId;
    this.alias = alias;
    this.destinationCurrency = destinationCurrency;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getOwnerCustomerId() {
    return ownerCustomerId;
  }

  public UUID getDestinationAccountId() {
    return destinationAccountId;
  }

  public String getAlias() {
    return alias;
  }

  public String getDestinationCurrency() {
    return destinationCurrency;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
