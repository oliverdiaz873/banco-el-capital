package com.bancoelcapital.customers.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$") String customerId,
    @Size(max = 128) String displayName) {}
