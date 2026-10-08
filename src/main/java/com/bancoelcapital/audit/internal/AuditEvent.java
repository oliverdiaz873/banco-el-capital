package com.bancoelcapital.audit.internal;

import java.time.Instant;
import java.util.UUID;

import com.bancoelcapital.audit.api.AuditEntry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

  @Id private UUID id;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(nullable = false, length = 128)
  private String actor;

  @Column(nullable = false, length = 64)
  private String action;

  @Column(name = "resource_type", nullable = false, length = 64)
  private String resourceType;

  @Column(name = "resource_id", nullable = false, length = 128)
  private String resourceId;

  @Column(nullable = false, length = 16)
  private String result;

  @Column(length = 2000)
  private String details;

  protected AuditEvent() {}

  public AuditEvent(AuditEntry entry) {
    this.id = UUID.randomUUID();
    this.occurredAt = Instant.now();
    this.actor = entry.actor();
    this.action = entry.action();
    this.resourceType = entry.resourceType();
    this.resourceId = entry.resourceId();
    this.result = entry.result();
    this.details = entry.details();
  }
}
