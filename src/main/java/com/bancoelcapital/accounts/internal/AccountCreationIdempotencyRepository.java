package com.bancoelcapital.accounts.internal;

import org.springframework.data.jpa.repository.JpaRepository;

interface AccountCreationIdempotencyRepository
    extends JpaRepository<AccountCreationIdempotency, String> {}
