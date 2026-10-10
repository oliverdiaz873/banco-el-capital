package com.bancoelcapital.accounts.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotency record for account-lifecycle transitions (ADR-06, ADR-19). Dedicated namespace: same
 * transition intent plus same key MUST NOT apply twice. Same key plus incompatible payload is a
 * conflict, resolved by comparing the stored request hash. Each new key is a distinct intent. The
 * account reference stays logical (no FK): unknown-account rejections persist evidence for ids that
 * resolve to nothing.
 */
@Entity
@Table(name = "account_transition_idempotency")
public class TransitionIdempotency {

  @Id
  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "account_id")
  private UUID accountId;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private TransitionOutcome outcome;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected TransitionIdempotency() {}

  public TransitionIdempotency(
      String idempotencyKey, UUID accountId, String requestHash, TransitionOutcome outcome) {
    this.idempotencyKey = idempotencyKey;
    this.accountId = accountId;
    this.requestHash = requestHash;
    this.outcome = outcome;
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

  public TransitionOutcome getOutcome() {
    return outcome;
  }
}
