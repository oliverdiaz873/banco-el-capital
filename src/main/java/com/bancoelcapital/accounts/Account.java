package com.bancoelcapital.accounts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Account aggregate root (ADR-03). Owns lifecycle, holder relationship, product reference and
 * operability. It MUST NOT calculate financial movements.
 */
@Entity
@Table(name = "accounts")
public class Account {

  @Id private UUID id;

  @Column(name = "holder_customer_id", nullable = false, length = 64)
  private String holderCustomerId;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "product_code", nullable = false, length = 32)
  private String productCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private AccountStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Account() {}

  public Account(UUID id, String holderCustomerId, String currency, String productCode) {
    this.id = id;
    this.holderCustomerId = holderCustomerId;
    this.currency = currency;
    this.productCode = productCode;
    this.status = AccountStatus.ACTIVE;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getHolderCustomerId() {
    return holderCustomerId;
  }

  public String getCurrency() {
    return currency;
  }

  public String getProductCode() {
    return productCode;
  }

  public AccountStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isOperable() {
    return status == AccountStatus.ACTIVE;
  }
}
