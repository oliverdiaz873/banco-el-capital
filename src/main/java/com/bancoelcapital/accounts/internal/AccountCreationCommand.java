package com.bancoelcapital.accounts.internal;

/** Intent to create an account for a holder (ADR-02 operation intent). */
public record AccountCreationCommand(
    String holderCustomerId, String currency, String productCode) {}
