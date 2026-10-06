package com.bancoelcapital.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateDepositRequest(
    @NotNull UUID accountId,
    @NotNull Long amountMinorUnits,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
