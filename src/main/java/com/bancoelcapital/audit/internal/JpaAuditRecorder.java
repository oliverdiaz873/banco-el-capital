package com.bancoelcapital.audit.internal;

import org.springframework.stereotype.Service;

import com.bancoelcapital.audit.api.AuditEntry;
import com.bancoelcapital.audit.api.AuditRecorder;

@Service
class JpaAuditRecorder implements AuditRecorder {

  private final AuditEventRepository repository;

  JpaAuditRecorder(AuditEventRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(AuditEntry entry) {
    repository.save(new AuditEvent(entry));
  }
}
