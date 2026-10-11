package com.bancoelcapital.accounts.api;

/**
 * Minimal destination currency carried by the beneficiary destination lookup (ADR-20, precision A).
 * Presence of this value means the destination account exists; absence (empty Optional) means it
 * does not. No holder, status, or balance is exposed.
 */
public record DestinationCurrency(String currency) {}
