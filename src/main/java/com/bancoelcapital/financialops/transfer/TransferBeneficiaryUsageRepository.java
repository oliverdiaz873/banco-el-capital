package com.bancoelcapital.financialops.transfer;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TransferBeneficiaryUsageRepository
    extends JpaRepository<TransferBeneficiaryUsage, UUID> {}
