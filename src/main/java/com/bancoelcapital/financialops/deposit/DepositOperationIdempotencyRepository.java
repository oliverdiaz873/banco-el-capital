package com.bancoelcapital.financialops.deposit;

import org.springframework.data.jpa.repository.JpaRepository;

interface DepositOperationIdempotencyRepository
    extends JpaRepository<DepositOperationIdempotency, String> {}
