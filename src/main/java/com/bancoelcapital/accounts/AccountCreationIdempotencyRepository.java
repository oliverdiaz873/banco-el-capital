package com.bancoelcapital.accounts;

import org.springframework.data.jpa.repository.JpaRepository;

interface AccountCreationIdempotencyRepository
    extends JpaRepository<AccountCreationIdempotency, String> {}
