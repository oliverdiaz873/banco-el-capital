package com.bancoelcapital.financialops.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

interface TransferOperationIdempotencyRepository
    extends JpaRepository<TransferOperationIdempotency, String> {}
