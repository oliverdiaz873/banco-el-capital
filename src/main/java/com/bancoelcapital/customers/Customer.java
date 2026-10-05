package com.bancoelcapital.customers;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Customer aggregate root (Identity/Customers module, ADR-01/ADR-09). Owns customer identity only.
 * It MUST NOT own accounts, holders relationships, or financial logic.
 */
@Entity
@Table(name = "customers")
public class Customer {

  @Id
  @Column(name = "customer_id", nullable = false, length = 64)
  private String customerId;

  @Column(name = "display_name", length = 128)
  private String displayName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false, length = 128)
  private String createdBy;

  protected Customer() {}

  public Customer(String customerId, String displayName, String createdBy) {
    this.customerId = customerId;
    this.displayName = displayName;
    this.createdBy = createdBy;
    this.createdAt = Instant.now();
  }

  public String getCustomerId() {
    return customerId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }
}
