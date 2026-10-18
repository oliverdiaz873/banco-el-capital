package com.bancoelcapital.financialops.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.bancoelcapital.accounts.api.AccountLookupService;
import com.bancoelcapital.financialops.core.FinancialOperationType;
import com.bancoelcapital.financialops.core.MovementDirection;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationDecision;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * Service-level keyset, authorization collapse and read-only guarantees (ADR-22), with repositories
 * and contracts mocked and the real codec.
 */
class MovementHistoryServiceTest {

  private final MovementHistoryRepository movements = mock(MovementHistoryRepository.class);
  private final AccountLookupService accounts = mock(AccountLookupService.class);
  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final MovementCursorCodec codec = new MovementCursorCodec();
  private final MovementHistoryService service =
      new MovementHistoryService(movements, accounts, authorization, codec);

  private final UUID accountId = UUID.randomUUID();
  private final AuthenticatedActor holder = new AuthenticatedActor("holder-1", Set.of());

  @Test
  void rejectsLimitsOutsideOneToMaxWithoutQueryingState() {
    assertInvalid(() -> service.history(accountId, 0, null, holder));
    assertInvalid(() -> service.history(accountId, 101, null, holder));
    verifyNoInteractions(movements, accounts, authorization);
  }

  @Test
  void rejectsMalformedCursorBeforeAnyStateLookup() {
    assertInvalid(() -> service.history(accountId, 20, "not-a-token", holder));
    verifyNoInteractions(movements, accounts, authorization);
  }

  @Test
  void unknownAccountIsNotFound() {
    when(accounts.findHolderCustomerId(accountId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.history(accountId, 20, null, holder))
        .isInstanceOf(MovementHistoryException.class)
        .satisfies(
            e ->
                assertThat(((MovementHistoryException) e).getKind())
                    .isEqualTo(MovementHistoryException.Kind.NOT_FOUND));
    verifyNoInteractions(authorization, movements);
  }

  @Test
  void authenticatedNonOwnerCollapsesToNotFoundNeverForbidden() {
    when(accounts.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideViewAccount(holder, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.history(accountId, 20, null, holder))
        .isInstanceOf(MovementHistoryException.class)
        .satisfies(
            e ->
                assertThat(((MovementHistoryException) e).getKind())
                    .isEqualTo(MovementHistoryException.Kind.NOT_FOUND));
    verifyNoInteractions(movements);
  }

  @Test
  void unauthenticatedActorOnExistingAccountIsUnauthenticated() {
    when(accounts.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideViewAccount(holder, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("unauthenticated actor"));

    assertThatThrownBy(() -> service.history(accountId, 20, null, holder))
        .isInstanceOf(MovementHistoryException.class)
        .satisfies(
            e ->
                assertThat(((MovementHistoryException) e).getKind())
                    .isEqualTo(MovementHistoryException.Kind.UNAUTHENTICATED));
    verifyNoInteractions(movements);
  }

  @Test
  void firstPageFetchesLimitPlusOneAndEmitsNextCursorForTheLastItem() {
    when(accounts.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideViewAccount(holder, "holder-1"))
        .thenReturn(AuthorizationDecision.allow());
    MovementHistoryEntry newest = entry(1_700_000_000_000_002L);
    MovementHistoryEntry second = entry(1_700_000_000_000_001L);
    MovementHistoryEntry extra = entry(1_700_000_000_000_000L);
    when(movements.findFirstPage(eq(accountId), any(Pageable.class)))
        .thenReturn(List.of(newest, second, extra));

    MovementHistoryPage page = service.history(accountId, 2, null, holder);

    assertThat(page.items()).containsExactly(newest, second);
    assertThat(page.nextCursor()).isNotNull();
    MovementCursorCodec.Decoded decoded = codec.decode(page.nextCursor());
    assertThat(decoded.createdAt()).isEqualTo(second.createdAt());
    assertThat(decoded.id()).isEqualTo(second.movementId());
    ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
    verify(movements).findFirstPage(eq(accountId), window.capture());
    assertThat(window.getValue().getPageSize()).isEqualTo(3);
  }

  @Test
  void laterPageUsesTheDecodedKeysetPosition() {
    when(accounts.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideViewAccount(holder, "holder-1"))
        .thenReturn(AuthorizationDecision.allow());
    MovementHistoryEntry cursorEntry = entry(1_700_000_000_000_001L);
    String cursor = codec.encode(cursorEntry.createdAt(), cursorEntry.movementId());
    MovementHistoryEntry remaining = entry(1_700_000_000_000_000L);
    when(movements.findPageAfter(eq(accountId), any(Instant.class), any(UUID.class), any()))
        .thenReturn(List.of(remaining));

    MovementHistoryPage page = service.history(accountId, 2, cursor, holder);

    assertThat(page.items()).containsExactly(remaining);
    assertThat(page.nextCursor()).isNull();
    verify(movements)
        .findPageAfter(
            accountId, cursorEntry.createdAt(), cursorEntry.movementId(), PageRequest.of(0, 3));
  }

  @Test
  void emptyHistoryReturnsEmptyPageWithoutCursor() {
    when(accounts.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideViewAccount(holder, "holder-1"))
        .thenReturn(AuthorizationDecision.allow());
    when(movements.findFirstPage(eq(accountId), any(Pageable.class))).thenReturn(List.of());

    MovementHistoryPage page = service.history(accountId, 20, null, holder);

    assertThat(page.items()).isEmpty();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void repositoryExposesNoWriteOperations() {
    assertThat(
            Arrays.stream(MovementHistoryRepository.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("save") || name.startsWith("delete")))
        .isEmpty();
  }

  private void assertInvalid(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOf(MovementHistoryException.class)
        .satisfies(
            e ->
                assertThat(((MovementHistoryException) e).getKind())
                    .isEqualTo(MovementHistoryException.Kind.INVALID));
  }

  private static MovementHistoryEntry entry(long micros) {
    return new MovementHistoryEntry(
        UUID.randomUUID(),
        UUID.randomUUID(),
        FinancialOperationType.DEPOSIT,
        MovementDirection.CREDIT,
        100L,
        "DOP",
        Instant.ofEpochSecond(
            Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L));
  }
}
