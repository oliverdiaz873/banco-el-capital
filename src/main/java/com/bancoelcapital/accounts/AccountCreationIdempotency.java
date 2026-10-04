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
 * Idempotency record for account creation (ADR-06). Same logical intent plus same key MUST NOT
 * create a second account. Same key plus incompatible payload is a conflict, resolved by comparing
 * the stored request hash. The holder is stored so the query endpoint can reuse the creation
 * authorization rule without leaking account ids to unauthorized actors.
 */
@Entity
@Table(name = "account_creation_idempotency")
public class AccountCreationIdempotency {

  @Id
  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "account_id")
  private UUID accountId;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private CreationOutcome outcome;

  @Column(name = "holder_customer_id", length = 64)
  private String holderCustomerId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AccountCreationIdempotency() {}

  public AccountCreationIdempotency(
      String idempotencyKey,
      UUID accountId,
      String requestHash,
      CreationOutcome outcome,
      String holderCustomerId) {
    this.idempotencyKey = idempotencyKey;
    this.accountId = accountId;
    this.requestHash = requestHash;
    this.outcome = outcome;
    this.holderCustomerId = holderCustomerId;
    this.createdAt = Instant.now();
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public CreationOutcome getOutcome() {
    return outcome;
  }

  public String getHolderCustomerId() {
    return holderCustomerId;
  }
}
