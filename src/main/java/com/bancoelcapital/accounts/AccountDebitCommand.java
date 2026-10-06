package com.bancoelcapital.accounts;

import java.util.UUID;

/**
 * Intent to apply a confirmed debit to an account balance (ADR-16). Owned by Accounts; consumed by
 * Financial Operations coordination. Money uses integer minor units.
 */
public record AccountDebitCommand(UUID accountId, Long amountMinorUnits, String currency) {}
