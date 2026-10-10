package com.bancoelcapital.accounts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.bancoelcapital.audit.api.AuditEntry;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationDecision;
import com.bancoelcapital.identity.AuthorizationService;

@ExtendWith(MockitoExtension.class)
class AccountTransitionServiceTest {

  @Mock AccountRepository accounts;
  @Mock TransitionIdempotencyRepository idempotency;
  @Mock AuditRecorder audit;
  @Mock AuthorizationService authorization;
  @Mock PlatformTransactionManager transactionManager;

  AccountTransitionService service;

  UUID accountId = UUID.randomUUID();
  AuthenticatedActor actor = new AuthenticatedActor("employee-1", Set.of("BANK_EMPLOYEE"));

  @BeforeEach
  void setup() {
    // Lenient: not every test reaches a transaction (key/replay short-circuits).
    lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(mock(TransactionStatus.class));
    // The unlocked holder read delegates to whatever the locked read returns; per-test
    // arrangements of findByIdForUpdate therefore also satisfy authorization input.
    lenient()
        .when(accounts.findById(accountId))
        .thenAnswer(ignored -> accounts.findByIdForUpdate(accountId));
    lenient()
        .when(authorization.decideTransition(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.allow());
    service =
        new AccountTransitionService(
            accounts, idempotency, audit, authorization, transactionManager);
  }

  private Account accountInStatus(AccountStatus status) {
    Account account = new Account(accountId, "holder-1", "DOP", "BASIC");
    account.applyStatus(status);
    return account;
  }

  private void unlockedSetup() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(accountInStatus(AccountStatus.ACTIVE)));
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
  }

  @Test
  void confirmsBlockWithStateAuditAndIdempotency() {
    unlockedSetup();

    TransitionResult result =
        service.transition(new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", actor);

    assertThat(result.changed()).isTrue();
    assertThat(result.status()).isEqualTo(AccountStatus.BLOCKED);
    assertThat(result.accountId()).isEqualTo(accountId);
    verify(accounts).save(any(Account.class));
    verify(idempotency).save(any(TransitionIdempotency.class));
    var confirmed = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(confirmed.capture());
    assertThat(confirmed.getValue().action()).isEqualTo("ACCOUNT_BLOCKED");
    assertThat(confirmed.getValue().result()).isEqualTo("CONFIRMED");
  }

  @Test
  void confirmsUnblockFromBlocked() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(accountInStatus(AccountStatus.BLOCKED)));
    when(idempotency.findById("k1")).thenReturn(Optional.empty());

    TransitionResult result =
        service.transition(
            new TransitionCommand(accountId, AccountTransition.UNBLOCK), "k1", actor);

    assertThat(result.changed()).isTrue();
    assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
    var confirmed = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(confirmed.capture());
    assertThat(confirmed.getValue().action()).isEqualTo("ACCOUNT_UNBLOCKED");
  }

  @Test
  void confirmsCloseWithZeroBalance() {
    unlockedSetup();

    TransitionResult result =
        service.transition(new TransitionCommand(accountId, AccountTransition.CLOSE), "k1", actor);

    assertThat(result.changed()).isTrue();
    assertThat(result.status()).isEqualTo(AccountStatus.CLOSED);
    var confirmed = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(confirmed.capture());
    assertThat(confirmed.getValue().action()).isEqualTo("ACCOUNT_CLOSED");
  }

  @Test
  void repeatsBlockOnBlockedAsNoOpWithoutAudit() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(accountInStatus(AccountStatus.BLOCKED)));
    when(idempotency.findById("k1")).thenReturn(Optional.empty());

    TransitionResult result =
        service.transition(new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", actor);

    assertThat(result.changed()).isFalse();
    assertThat(result.status()).isEqualTo(AccountStatus.BLOCKED);
    verify(accounts, never()).save(any());
    verify(audit, never()).record(any());
    var stored = ArgumentCaptor.forClass(TransitionIdempotency.class);
    verify(idempotency).save(stored.capture());
    assertThat(stored.getValue().getOutcome()).isEqualTo(TransitionOutcome.CONFIRMED);
  }

  @Test
  void closeOnClosedIsRejectedNeverNoOp() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(accountInStatus(AccountStatus.CLOSED)));
    when(idempotency.findById("k1")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.transition(
                    new TransitionCommand(accountId, AccountTransition.CLOSE), "k1", actor))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.REJECTED);
    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsUnknownAccountWithoutStateChange() {
    // Holder resolution fails before any idempotency read: the unknown-account rejection
    // short-circuits, so no pre-check stub is needed here.
    when(accounts.findById(accountId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.transition(
                    new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", actor))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.REJECTED);
    verify(accounts, never()).save(any());
    var rejected = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(rejected.capture());
    assertThat(rejected.getValue().action()).isEqualTo("ACCOUNT_TRANSITION_REJECTED");
  }

  @Test
  void rejectsCloseWithNonZeroBalance() {
    Account funded = accountInStatus(AccountStatus.ACTIVE);
    funded.applyCredit(10_00L);
    when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(funded));
    when(idempotency.findById("k1")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.transition(
                    new TransitionCommand(accountId, AccountTransition.CLOSE), "k1", actor))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.REJECTED)
        .hasMessageContaining("non-zero balance");
    verify(accounts, never()).save(any());
  }

  @Test
  void requiresIdempotencyKey() {
    assertThatThrownBy(
            () ->
                service.transition(
                    new TransitionCommand(accountId, AccountTransition.BLOCK), " ", actor))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.FAILED);
  }

  @Test
  void replaysSameKeyWithoutNewEffects() {
    String hash =
        AccountTransitionService.requestHash(
            new TransitionCommand(accountId, AccountTransition.BLOCK));
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new TransitionIdempotency("k1", accountId, hash, TransitionOutcome.CONFIRMED)));
    when(accounts.findById(accountId))
        .thenReturn(Optional.of(accountInStatus(AccountStatus.BLOCKED)));

    TransitionResult result =
        service.transition(new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", actor);

    assertThat(result.changed()).isFalse();
    assertThat(result.status()).isEqualTo(AccountStatus.BLOCKED);
    verify(accounts, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void conflictsOnSameKeyWithDifferentPayload() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(accountInStatus(AccountStatus.ACTIVE)));
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new TransitionIdempotency(
                    "k1", accountId, "other-hash", TransitionOutcome.CONFIRMED)));

    assertThatThrownBy(
            () ->
                service.transition(
                    new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", actor))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.CONFLICT);
    var conflict = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(conflict.capture());
    assertThat(conflict.getValue().action()).isEqualTo("ACCOUNT_TRANSITION_CONFLICT");
  }

  @Test
  void differentKeysAreIndependentTransitions() {
    // Fresh ACTIVE row per attempt: the mock would otherwise hand back the object mutated
    // by the first transition.
    when(accounts.findByIdForUpdate(accountId))
        .thenAnswer(ignored -> Optional.of(accountInStatus(AccountStatus.ACTIVE)));
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
    when(idempotency.findById("k2")).thenReturn(Optional.empty());

    TransitionResult first =
        service.transition(new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", actor);
    TransitionResult second =
        service.transition(new TransitionCommand(accountId, AccountTransition.BLOCK), "k2", actor);

    assertThat(first.changed()).isTrue();
    assertThat(second.changed()).isTrue();
  }

  @Test
  void postLockRecheckReplaysCommittedWinnerWithoutWriting() {
    String hash =
        AccountTransitionService.requestHash(
            new TransitionCommand(accountId, AccountTransition.BLOCK));
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(accountInStatus(AccountStatus.ACTIVE)));
    // Pre-check sees nothing; the post-lock re-check finds the committed winner.
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.empty(),
            Optional.of(
                new TransitionIdempotency("k1", accountId, hash, TransitionOutcome.CONFIRMED)));
    when(accounts.findById(accountId))
        .thenReturn(Optional.of(accountInStatus(AccountStatus.BLOCKED)));

    TransitionResult replayed =
        service.transition(new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", actor);

    assertThat(replayed.changed()).isFalse();
    assertThat(replayed.status()).isEqualTo(AccountStatus.BLOCKED);
    verify(accounts, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void concurrentUnknownOutcomeIsUnknownNeverFailed() {
    unlockedSetup();
    doThrow(new DataIntegrityViolationException("duplicate key")).when(accounts).flush();
    when(idempotency.findById("k1")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.transition(
                    new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", actor))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.UNKNOWN)
        .hasMessageContaining("same idempotency key");
  }

  @Test
  void deniesUnauthorizedAndUnauthenticatedActors() {
    when(accounts.findByIdForUpdate(accountId))
        .thenReturn(Optional.of(accountInStatus(AccountStatus.ACTIVE)));
    when(authorization.decideTransition(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(
            () ->
                service.transition(
                    new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", actor))
        .isInstanceOf(TransitionException.class)
        .matches(e -> ((TransitionException) e).getKind() == TransitionException.Kind.FORBIDDEN);

    var anonymous = new AuthenticatedActor(null, Set.of());
    when(authorization.decideTransition(anonymous, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("unauthenticated actor"));

    assertThatThrownBy(
            () ->
                service.transition(
                    new TransitionCommand(accountId, AccountTransition.BLOCK), "k1", anonymous))
        .isInstanceOf(TransitionException.class)
        .matches(
            e -> ((TransitionException) e).getKind() == TransitionException.Kind.UNAUTHENTICATED);
    verify(accounts, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void authorizedQueryReturnsStoredOutcome() {
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new TransitionIdempotency("k1", accountId, "hash", TransitionOutcome.CONFIRMED)));

    var found = service.outcomeOf("k1");

    assertThat(found).contains(TransitionOutcome.CONFIRMED);
  }
}
