package com.bancoelcapital.audit;

import org.springframework.stereotype.Service;

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
