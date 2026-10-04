package com.bancoelcapital.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
    @NotBlank String holderCustomerId,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
    @NotBlank String productCode) {}
