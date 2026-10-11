package com.bancoelcapital.beneficiaries.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateBeneficiaryRequest(
    @NotNull UUID destinationAccountId, @NotBlank String alias) {}
