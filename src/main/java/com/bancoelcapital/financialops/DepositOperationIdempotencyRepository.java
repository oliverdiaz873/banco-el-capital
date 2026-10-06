package com.bancoelcapital.financialops;

import org.springframework.data.jpa.repository.JpaRepository;

interface DepositOperationIdempotencyRepository
    extends JpaRepository<DepositOperationIdempotency, String> {}
