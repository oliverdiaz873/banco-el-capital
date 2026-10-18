package com.bancoelcapital.financialops.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.bancoelcapital.financialops.core.Movement;

/**
 * Dedicated read-only keyset repository for the account movement history (ADR-22). Deliberately not
 * the write-path {@code MovementRepository}: it exposes no save/delete method, so the read path
 * cannot mutate state, and only the requested account's movements are ever selected.
 *
 * <p>{@code operationType} lives on {@code financial_operations}, so it is joined in; the query
 * never selects or reconstructs the sibling leg's {@code account_id}.
 */
public interface MovementHistoryRepository extends Repository<Movement, UUID> {

  @Query(
      "select new com.bancoelcapital.financialops.history.MovementHistoryEntry("
          + "m.id, m.operationId, o.operationType, m.direction, m.amountMinorUnits, m.currency,"
          + " m.createdAt)"
          + " from Movement m, FinancialOperation o"
          + " where m.operationId = o.id and m.accountId = :accountId"
          + " order by m.createdAt desc, m.id desc")
  List<MovementHistoryEntry> findFirstPage(@Param("accountId") UUID accountId, Pageable pageable);

  @Query(
      "select new com.bancoelcapital.financialops.history.MovementHistoryEntry("
          + "m.id, m.operationId, o.operationType, m.direction, m.amountMinorUnits, m.currency,"
          + " m.createdAt)"
          + " from Movement m, FinancialOperation o"
          + " where m.operationId = o.id and m.accountId = :accountId"
          + " and (m.createdAt < :createdAt or (m.createdAt = :createdAt and m.id < :id))"
          + " order by m.createdAt desc, m.id desc")
  List<MovementHistoryEntry> findPageAfter(
      @Param("accountId") UUID accountId,
      @Param("createdAt") Instant createdAt,
      @Param("id") UUID id,
      Pageable pageable);
}
