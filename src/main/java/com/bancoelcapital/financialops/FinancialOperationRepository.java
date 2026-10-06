package com.bancoelcapital.financialops;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FinancialOperationRepository extends JpaRepository<FinancialOperation, UUID> {}
