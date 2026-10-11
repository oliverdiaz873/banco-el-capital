package com.bancoelcapital.beneficiaries.web;

import java.util.UUID;

public record BeneficiaryResponse(UUID beneficiaryId, boolean created) {}
