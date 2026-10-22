package com.bancoelcapital.beneficiaries.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

  List<Beneficiary> findByOwnerCustomerIdOrderByCreatedAtAsc(String ownerCustomerId);

  boolean existsByOwnerCustomerIdAndDestinationAccountId(
      String ownerCustomerId, UUID destinationAccountId);

  Optional<Beneficiary> findByIdAndOwnerCustomerId(UUID id, String ownerCustomerId);

  /**
   * Projection-only owner-scoped read used by transfer authorization and post-lock revalidation. It
   * deliberately does not load the Beneficiary entity: a managed stale instance from the phase-two
   * read would otherwise be returned by the post-lock query without observing a concurrent
   * destination/owner change.
   */
  @Query(
      "select new com.bancoelcapital.beneficiaries.internal.BeneficiaryView("
          + "b.id, b.alias, b.destinationAccountId, b.destinationCurrency, b.createdAt)"
          + " from Beneficiary b where b.id = :id and b.ownerCustomerId = :ownerCustomerId")
  Optional<BeneficiaryView> findViewAndOwner(UUID id, String ownerCustomerId);
}
