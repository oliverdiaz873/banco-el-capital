package com.bancoelcapital.accounts.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, UUID> {

  /**
   * Loads the account row with a write lock (SELECT ... FOR UPDATE). Serializes concurrent balance
   * mutations so parallel credits never lose updates, on H2 and PostgreSQL alike. Used only by the
   * Accounts-owned balance application contract.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Account a where a.id = :id")
  Optional<Account> findByIdForUpdate(UUID id);
}
