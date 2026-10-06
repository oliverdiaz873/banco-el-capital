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
 * Account aggregate root (ADR-03). Owns lifecycle, holder relationship, product reference,
 * operability, and stored balance state (ADR-15). It MUST NOT calculate financial movements;
 * balance changes only through an Accounts-owned contract applied by Financial Operations
 * coordination.
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

  @Column(name = "balance_minor_units", nullable = false)
  private Long balanceMinorUnits;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Account() {}

  public Account(UUID id, String holderCustomerId, String currency, String productCode) {
    this.id = id;
    this.holderCustomerId = holderCustomerId;
    this.currency = currency;
    this.productCode = productCode;
    this.status = AccountStatus.ACTIVE;
    this.balanceMinorUnits = 0L;
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

  public Long getBalanceMinorUnits() {
    return balanceMinorUnits;
  }

  /**
   * Applies a confirmed credit to stored balance (ADR-15). Overflow can never wrap silently;
   * callers treat the arithmetic failure as a technical outcome, not a business rejection.
   */
  public void applyCredit(long amountMinorUnits) {
    this.balanceMinorUnits = Math.addExact(this.balanceMinorUnits, amountMinorUnits);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isOperable() {
    return status == AccountStatus.ACTIVE;
  }
}
