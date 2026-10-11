package com.bancoelcapital.beneficiaries.internal;

import java.util.UUID;

/**
 * Queryable creation outcome for timeout/disconnect recovery. The beneficiary id is null for a
 * stored REJECTED outcome.
 */
public record BeneficiaryOutcome(UUID beneficiaryId, BeneficiaryCreationOutcome outcome) {}
