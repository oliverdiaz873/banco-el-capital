package com.bancoelcapital.financialops.history.web;

import java.time.Instant;
import java.util.List;

/**
 * Read-only movement history response (ADR-22). Carries no account identifier of any kind: neither
 * the queried account nor a third party's. {@code counterparty} is a generic descriptor, never a
 * third-party account id or PII, and is {@code null} end-to-end when there is no counterparty.
 * {@code nextCursor} is {@code null} at the end of the history.
 */
public record MovementHistoryResponse(List<Item> items, String nextCursor) {

  public record Item(
      String movementId,
      String operationId,
      String operationType,
      String direction,
      long amountMinorUnits,
      String currency,
      Instant createdAt,
      Counterparty counterparty) {}

  /** Generic counterparty descriptor; {@code null} for deposit/withdrawal, TRANSFER otherwise. */
  public record Counterparty(String type) {}
}
