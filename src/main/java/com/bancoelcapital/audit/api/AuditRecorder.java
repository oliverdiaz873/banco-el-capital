package com.bancoelcapital.audit.api;

/** Transverse audit capability contract (ADR-01/ADR-11). */
public interface AuditRecorder {
  void record(AuditEntry entry);
}
