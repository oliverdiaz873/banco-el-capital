package com.bancoelcapital.beneficiaries.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.beneficiaries.internal.BeneficiaryService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Owner-scoped read-only beneficiary resolution (Beneficiaries public contract, ADR-21). Joins the
 * caller's transaction (REQUIRED, read-only) and never creates or suspends one; takes no row lock.
 * A missing row and another owner's row both resolve to empty, so callers cannot distinguish them.
 */
@Service
public class BeneficiaryResolver {

  private final BeneficiaryService beneficiaries;

  public BeneficiaryResolver(BeneficiaryService beneficiaries) {
    this.beneficiaries = beneficiaries;
  }

  @Transactional(readOnly = true)
  public Optional<BeneficiaryResolution> resolveForOwner(
      UUID beneficiaryId, String ownerCustomerId) {
    if (beneficiaryId == null || ownerCustomerId == null || ownerCustomerId.isBlank()) {
      return Optional.empty();
    }
    return beneficiaries
        .findForOwner(beneficiaryId, new AuthenticatedActor(ownerCustomerId, Set.of()))
        .map(
            view ->
                new BeneficiaryResolution(
                    view.beneficiaryId(),
                    ownerCustomerId,
                    view.destinationAccountId(),
                    view.destinationCurrency(),
                    view.alias()));
  }
}
