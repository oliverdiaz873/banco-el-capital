package com.bancoelcapital.beneficiaries.internal;

import java.util.UUID;

/** Determinable beneficiary creation result: beneficiary plus whether this attempt created it. */
public record BeneficiaryResult(UUID beneficiaryId, boolean created) {}
