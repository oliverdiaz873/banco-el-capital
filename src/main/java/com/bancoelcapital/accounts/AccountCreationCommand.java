package com.bancoelcapital.accounts;

/** Intent to create an account for a holder (ADR-02 operation intent). */
public record AccountCreationCommand(
    String holderCustomerId, String currency, String productCode) {}
