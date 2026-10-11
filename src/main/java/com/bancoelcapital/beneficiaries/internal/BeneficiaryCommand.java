package com.bancoelcapital.beneficiaries.internal;

import java.util.UUID;

/** Intent to save a destination account as a beneficiary with a human alias (ADR-20). */
public record BeneficiaryCommand(UUID destinationAccountId, String alias) {}
