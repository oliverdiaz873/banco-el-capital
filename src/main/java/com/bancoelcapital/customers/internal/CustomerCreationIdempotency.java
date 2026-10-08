package com.bancoelcapital.customers.internal;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotency record for customer creation (ADR-06). Same logical intent plus same key MUST NOT
 * create a second customer. Same key plus incompatible payload is a conflict, resolved by comparing
 * the stored request hash.
 */
@Entity
@Table(name = "customer_creation_idempotency")
public class CustomerCreationIdempotency {

  @Id
  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "customer_id", length = 64)
  private String customerId;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private CustomerCreationOutcome outcome;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected CustomerCreationIdempotency() {}

  public CustomerCreationIdempotency(
      String idempotencyKey,
      String customerId,
      String requestHash,
      CustomerCreationOutcome outcome) {
    this.idempotencyKey = idempotencyKey;
    this.customerId = customerId;
    this.requestHash = requestHash;
    this.outcome = outcome;
    this.createdAt = Instant.now();
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getCustomerId() {
    return customerId;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public CustomerCreationOutcome getOutcome() {
    return outcome;
  }
}
