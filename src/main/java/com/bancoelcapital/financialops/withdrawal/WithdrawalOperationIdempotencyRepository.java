package com.bancoelcapital.financialops.withdrawal;

import org.springframework.data.jpa.repository.JpaRepository;

interface WithdrawalOperationIdempotencyRepository
    extends JpaRepository<WithdrawalOperationIdempotency, String> {}
