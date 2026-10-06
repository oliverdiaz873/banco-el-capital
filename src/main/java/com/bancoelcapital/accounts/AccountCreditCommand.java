package com.bancoelcapital.accounts;

import java.util.UUID;

/**
 * Intent to apply a confirmed credit to an account balance (ADR-15). Owned by Accounts; consumed by
 * Financial Operations coordination. Money uses integer minor units.
 */
public record AccountCreditCommand(UUID accountId, Long amountMinorUnits, String currency) {}
