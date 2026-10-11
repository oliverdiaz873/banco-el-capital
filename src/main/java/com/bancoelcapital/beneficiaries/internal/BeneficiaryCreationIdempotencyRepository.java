package com.bancoelcapital.beneficiaries.internal;

import org.springframework.data.jpa.repository.JpaRepository;

interface BeneficiaryCreationIdempotencyRepository
    extends JpaRepository<BeneficiaryCreationIdempotency, String> {}
