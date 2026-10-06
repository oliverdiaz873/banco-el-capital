package com.bancoelcapital.accounts;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts-owned read contract (ADR-01). Exposes only the holder identity a module needs to
 * authorize operations on an account; never the Account entity or its persistence. Consumed by
 * Financial Operations coordination.
 */
@Service
public class AccountLookupService {

  private final AccountRepository accounts;

  public AccountLookupService(AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Transactional(readOnly = true)
  public Optional<String> findHolderCustomerId(UUID accountId) {
    if (accountId == null) {
      return Optional.empty();
    }
    return accounts.findById(accountId).map(Account::getHolderCustomerId);
  }
}
