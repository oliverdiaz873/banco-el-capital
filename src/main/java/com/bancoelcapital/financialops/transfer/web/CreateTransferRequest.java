package com.bancoelcapital.financialops.transfer.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@TransferDestinationXor
public record CreateTransferRequest(
    @NotNull UUID sourceAccountId,
    UUID destinationAccountId,
    UUID beneficiaryId,
    @NotNull Long amountMinorUnits,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
