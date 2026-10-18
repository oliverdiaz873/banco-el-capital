package com.bancoelcapital.financialops.history;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.accounts.api.AccountLookupService;
import com.bancoelcapital.financialops.history.MovementCursorCodec.Decoded;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * Read-only account movement history (ADR-22): the confirmed effects of one account, newest first,
 * keyset-paginated by exact {@code (created_at, id)}. Dependency direction stays inside Financial
 * Operations: it reads the account holder through {@code accounts.api} and authorizes through
 * {@code identity} only, never reaching another module's internals.
 *
 * <p>Authorization deliberately mirrors the existing account read ({@code
 * AccountTransitionService.findAccountView}): existence first, then the decision. Both the unknown
 * account and the authenticated non-owner collapse to {@code NOT_FOUND} so neither reveals the
 * other; only the unauthenticated actor on an existing account stays a distinct {@code
 * UNAUTHENTICATED} (the inherited, documented asymmetry). The read takes no lock, writes nothing
 * and records no audit event.
 */
@Service
public class MovementHistoryService {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  /** One extra row fetched to learn whether a {@code nextCursor} exists without a count query. */
  private static final int CURSOR_WINDOW = 1;

  private final MovementHistoryRepository movements;
  private final AccountLookupService accounts;
  private final AuthorizationService authorization;
  private final MovementCursorCodec codec;

  public MovementHistoryService(
      MovementHistoryRepository movements,
      AccountLookupService accounts,
      AuthorizationService authorization,
      MovementCursorCodec codec) {
    this.movements = movements;
    this.accounts = accounts;
    this.authorization = authorization;
    this.codec = codec;
  }

  @Transactional(readOnly = true)
  public MovementHistoryPage history(
      UUID accountId, int limit, String cursor, AuthenticatedActor actor) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new MovementHistoryException(MovementHistoryException.Kind.INVALID, "invalid limit");
    }
    // Input format is validated before any state lookup: a malformed request is rejected as such,
    // independently of account existence or authorization.
    Decoded decoded = cursor == null ? null : codec.decode(cursor);
    Optional<String> holder = accounts.findHolderCustomerId(accountId);
    if (holder.isEmpty()) {
      throw notFound();
    }
    var decision = authorization.decideViewAccount(actor, holder.get());
    if (!decision.allowed()) {
      if (unauthenticated(decision.reason())) {
        throw new MovementHistoryException(
            MovementHistoryException.Kind.UNAUTHENTICATED, "unauthenticated actor");
      }
      throw notFound();
    }
    PageRequest window = PageRequest.of(0, limit + CURSOR_WINDOW);
    List<MovementHistoryEntry> rows =
        decoded == null
            ? movements.findFirstPage(accountId, window)
            : movements.findPageAfter(accountId, decoded.createdAt(), decoded.id(), window);
    boolean hasMore = rows.size() > limit;
    List<MovementHistoryEntry> items = hasMore ? rows.subList(0, limit) : rows;
    String nextCursor = null;
    if (hasMore) {
      MovementHistoryEntry last = items.get(items.size() - 1);
      nextCursor = codec.encode(last.createdAt(), last.movementId());
    }
    return new MovementHistoryPage(List.copyOf(items), nextCursor);
  }

  private static boolean unauthenticated(String reason) {
    return reason != null && reason.contains("unauthenticated");
  }

  private static MovementHistoryException notFound() {
    return new MovementHistoryException(MovementHistoryException.Kind.NOT_FOUND, "not found");
  }
}
