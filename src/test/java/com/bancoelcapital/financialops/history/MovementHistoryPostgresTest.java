package com.bancoelcapital.financialops.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.postgres.AbstractPostgresTest;

/**
 * Mandatory PostgreSQL gate for the movement history keyset (ADR-22). It proves on the target
 * engine that real {@code TIMESTAMPTZ} ordering and microsecond fidelity hold: an exact {@code
 * (created_at, id)} keyset over a STATIC set with {@code created_at} ties and a negative value near
 * the epoch, traversed across pages with no duplicates and no omissions. The static-set guarantee
 * deliberately does NOT extend to concurrent inserts; the read is not a consistent snapshot.
 *
 * <p>Skipped without Docker and without {@code BANCO_PG_*} (a skip proves nothing); a skip does NOT
 * satisfy the gate.
 */
class MovementHistoryPostgresTest extends AbstractPostgresTest {

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID D = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final UUID E = UUID.fromString("00000000-0000-0000-0000-000000000009");

  private static final Instant TIE = Instant.parse("2024-06-01T10:00:00.000005Z");
  private static final Instant LOWER = Instant.parse("2024-06-01T10:00:00.000004Z");
  private static final Instant NEGATIVE = Instant.parse("1969-12-31T23:59:59.999999Z");
  private static final Instant FUTURE = Instant.parse("2030-01-01T00:00:00.000001Z");

  @Autowired MovementHistoryService service;

  @Autowired MovementCursorCodec codec;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor holder;

  @BeforeEach
  void seedStaticSet() {
    accountId = UUID.randomUUID();
    holder = new AuthenticatedActor("holder-pg-mh", Set.of());
    jdbc.update(
        "insert into accounts (id, holder_customer_id, currency, product_code, status)"
            + " values (?, ?, ?, ?, ?)",
        accountId,
        "holder-pg-mh",
        "DOP",
        "BASIC",
        "ACTIVE");
    insert(A, TIE);
    insert(B, TIE);
    insert(C, LOWER);
    insert(D, NEGATIVE);
    insert(E, FUTURE);
  }

  @Test
  @Transactional
  void keysetTraversalIsExactOverAStaticSetWithTiesAndNegativeEpoch() {
    List<UUID> collected = new ArrayList<>();
    String cursor = null;
    do {
      MovementHistoryPage page = service.history(accountId, 2, cursor, holder);
      page.items().forEach(item -> collected.add(item.movementId()));
      cursor = page.nextCursor();
    } while (cursor != null);

    // created_at DESC, id DESC: future, then the TIE pair by descending id, then LOWER, then the
    // negative value near the epoch. No duplicates and no omissions.
    assertThat(collected).containsExactly(E, B, A, C, D);
  }

  @Test
  @Transactional
  void tieIsResolvedByDescendingIdWithinThePage() {
    MovementHistoryPage page = service.history(accountId, 3, null, holder);

    assertThat(page.items()).extracting(MovementHistoryEntry::movementId).containsExactly(E, B, A);
  }

  @Test
  @Transactional
  void cursorPreservesStoredMicrosecondsEndToEnd() {
    MovementHistoryPage first = service.history(accountId, 2, null, holder);

    String token = first.nextCursor();
    assertThat(token).isNotNull().hasSizeBetween(51, 76);
    MovementCursorCodec.Decoded decoded = codec.decode(token);
    assertThat(decoded.id()).isEqualTo(B);
    assertThat(decoded.createdAt()).isEqualTo(TIE);
    assertThat(decoded.createdAt().getNano()).isEqualTo(5_000);
  }

  @Test
  @Transactional
  void negativeEpochMovementIsReachedOnTheLastPage() {
    MovementHistoryPage last = service.history(accountId, 2, pageStartAfterC(), holder);

    assertThat(last.items()).extracting(MovementHistoryEntry::movementId).containsExactly(D);
    assertThat(last.nextCursor()).isNull();
    assertThat(last.items().get(0).createdAt()).isEqualTo(NEGATIVE);
  }

  private String pageStartAfterC() {
    // Walk two pages to obtain the cursor positioned after C, then the third page holds D.
    MovementHistoryPage first = service.history(accountId, 2, null, holder);
    MovementHistoryPage second = service.history(accountId, 2, first.nextCursor(), holder);
    assertThat(second.items()).extracting(MovementHistoryEntry::movementId).containsExactly(A, C);
    return second.nextCursor();
  }

  private void insert(UUID movementId, Instant createdAt) {
    UUID operationId = UUID.randomUUID();
    jdbc.update(
        "insert into financial_operations (id, operation_type, actor, account_id,"
            + " amount_minor_units, currency, status) values (?, ?, ?, ?, ?, ?, ?)",
        operationId,
        "DEPOSIT",
        "holder-pg-mh",
        accountId,
        100L,
        "DOP",
        "CONFIRMED");
    jdbc.update(
        "insert into movements (id, operation_id, account_id, direction, amount_minor_units,"
            + " currency, created_at) values (?, ?, ?, ?, ?, ?, ?)",
        movementId,
        operationId,
        accountId,
        "CREDIT",
        100L,
        "DOP",
        OffsetDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC));
  }
}
