package com.bancoelcapital.financialops;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface MovementRepository extends JpaRepository<Movement, UUID> {}
