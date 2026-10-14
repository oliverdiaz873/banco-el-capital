package com.bancoelcapital.beneficiaries.api;

import java.util.UUID;

/**
 * Saved-beneficiary destination reference exposed to other modules (Beneficiaries public contract,
 * ADR-21). Carries only what a consumer needs to resolve an owner-scoped beneficiary: never holder
 * identity of the destination, balances, or account status.
 */
public record BeneficiaryResolution(
    UUID beneficiaryId,
    String ownerCustomerId,
    UUID destinationAccountId,
    String destinationCurrency,
    String aliasSnapshot) {}
