package com.bancoelcapital.audit.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {}
