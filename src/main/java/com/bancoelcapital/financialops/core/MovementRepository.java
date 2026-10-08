package com.bancoelcapital.financialops.core;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MovementRepository extends JpaRepository<Movement, UUID> {}
