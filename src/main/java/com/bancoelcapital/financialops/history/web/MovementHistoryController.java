package com.bancoelcapital.financialops.history.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bancoelcapital.financialops.history.MovementHistoryEntry;
import com.bancoelcapital.financialops.history.MovementHistoryException;
import com.bancoelcapital.financialops.history.MovementHistoryPage;
import com.bancoelcapital.financialops.history.MovementHistoryService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * API boundary for the read-only account movement history (ADR-22). Versioned, keyset-paginated,
 * safe errors. The actor is read directly from {@code X-Actor-Id} as elsewhere today; the web layer
 * never sees a JPA entity. It maps the service page to generic descriptors and never echoes the
 * requested or any third-party account id.
 */
@RestController
@RequestMapping("/api/v1")
public class MovementHistoryController {

  private final MovementHistoryService service;

  public MovementHistoryController(MovementHistoryService service) {
    this.service = service;
  }

  @GetMapping("/accounts/{accountId}/movements")
  public ResponseEntity<MovementHistoryResponse> movements(
      @PathVariable UUID accountId,
      @RequestParam(value = "limit", required = false) String limit,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
    MovementHistoryPage page =
        service.history(
            accountId, parseLimit(limit), cursor, new AuthenticatedActor(actorId, Set.of()));
    return ResponseEntity.ok(toResponse(page));
  }

  private static int parseLimit(String limit) {
    if (limit == null) {
      return MovementHistoryService.DEFAULT_LIMIT;
    }
    int value;
    try {
      value = Integer.parseInt(limit);
    } catch (NumberFormatException e) {
      throw invalid();
    }
    if (value < 1 || value > MovementHistoryService.MAX_LIMIT) {
      throw invalid();
    }
    return value;
  }

  private static MovementHistoryException invalid() {
    return new MovementHistoryException(MovementHistoryException.Kind.INVALID, "invalid limit");
  }

  private static MovementHistoryResponse toResponse(MovementHistoryPage page) {
    List<MovementHistoryResponse.Item> items =
        page.items().stream().map(MovementHistoryController::toItem).toList();
    return new MovementHistoryResponse(items, page.nextCursor());
  }

  private static MovementHistoryResponse.Item toItem(MovementHistoryEntry entry) {
    MovementHistoryResponse.Counterparty counterparty =
        "TRANSFER".equals(entry.operationType().name())
            ? new MovementHistoryResponse.Counterparty("TRANSFER")
            : null;
    return new MovementHistoryResponse.Item(
        entry.movementId().toString(),
        entry.operationId().toString(),
        entry.operationType().name(),
        entry.direction().name(),
        entry.amountMinorUnits(),
        entry.currency(),
        entry.createdAt(),
        counterparty);
  }
}
