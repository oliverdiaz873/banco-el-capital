package com.bancoelcapital.audit.api;

/**
 * Evidence produced by a domain module for the transverse Audit capability (ADR-11). Modules
 * produce evidence; they MUST NOT own the audit store.
 */
public record AuditEntry(
    String actor,
    String action,
    String resourceType,
    String resourceId,
    String result,
    String details) {}
