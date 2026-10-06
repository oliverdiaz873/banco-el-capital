package com.bancoelcapital.financialops;

import org.springframework.data.jpa.repository.JpaRepository;

interface WithdrawalOperationIdempotencyRepository
    extends JpaRepository<WithdrawalOperationIdempotency, String> {}
