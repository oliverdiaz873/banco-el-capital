package com.bancoelcapital.accounts.api;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.accounts.internal.AccountRepository;

/**
 * Accounts-owned destination read contract (ADR-20, precision A). Exposes only whether a
 * destination account exists and, when it does, its currency — the exact minimum Beneficiaries
 * needs to validate a saved destination. Never the holder, status, or balance. Consumed by the
 * Beneficiaries module; Financial Operations keeps using its own credit/debit/lookup contracts.
 */
@Service
public class AccountDestinationLookup {

  private final AccountRepository accounts;

  public AccountDestinationLookup(AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Transactional(readOnly = true)
  public Optional<DestinationCurrency> findDestination(UUID accountId) {
    if (accountId == null) {
      return Optional.empty();
    }
    return accounts
        .findById(accountId)
        .map(account -> new DestinationCurrency(account.getCurrency()));
  }
}
