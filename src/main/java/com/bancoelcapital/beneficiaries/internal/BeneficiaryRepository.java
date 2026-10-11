package com.bancoelcapital.beneficiaries.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

  List<Beneficiary> findByOwnerCustomerIdOrderByCreatedAtAsc(String ownerCustomerId);

  boolean existsByOwnerCustomerIdAndDestinationAccountId(
      String ownerCustomerId, UUID destinationAccountId);

  Optional<Beneficiary> findByIdAndOwnerCustomerId(UUID id, String ownerCustomerId);
}
