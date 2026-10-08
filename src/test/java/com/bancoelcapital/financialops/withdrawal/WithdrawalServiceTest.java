package com.bancoelcapital.financialops.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.bancoelcapital.accounts.api.AccountDebitCommand;
import com.bancoelcapital.accounts.api.AccountDebitResult;
import com.bancoelcapital.accounts.api.AccountDebitService;
import com.bancoelcapital.accounts.api.AccountLookupService;
import com.bancoelcapital.audit.api.AuditEntry;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.financialops.core.FinancialOperation;
import com.bancoelcapital.financialops.core.FinancialOperationRepository;
import com.bancoelcapital.financialops.core.FinancialOperationStatus;
import com.bancoelcapital.financialops.core.FinancialOperationType;
import com.bancoelcapital.financialops.core.Movement;
import com.bancoelcapital.financialops.core.MovementRepository;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationDecision;
import com.bancoelcapital.identity.AuthorizationService;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

  @Mock FinancialOperationRepository operations;
  @Mock MovementRepository movements;
  @Mock WithdrawalOperationIdempotencyRepository idempotency;
  @Mock AccountDebitService debits;
  @Mock AccountLookupService lookup;
  @Mock AuthorizationService authorization;
  @Mock AuditRecorder audit;
  @Mock PlatformTransactionManager transactionManager;

  WithdrawalService service;

  UUID accountId = UUID.randomUUID();
  AuthenticatedActor actor = new AuthenticatedActor("holder-1", Set.of());
  WithdrawalCommand command = new WithdrawalCommand(accountId, 40_00L, "DOP");

  @BeforeEach
  void setup() {
    // Lenient: not every test reaches a transaction (auth/key/replay short-circuits).
    lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(mock(TransactionStatus.class));
    service =
        new WithdrawalService(
            operations,
            movements,
            idempotency,
            debits,
            lookup,
            authorization,
            audit,
            transactionManager);
  }

  private void emptyIdempotency() {
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideWithdraw(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
  }

  private void appliedDebit() {
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenReturn(new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.APPLIED, 60_00L));
  }

  @Test
  void confirmsWithdrawalWithSingleOperationMovementAndBalanceEffect() {
    emptyIdempotency();
    appliedDebit();

    WithdrawalResult result = service.withdraw(command, "k1", actor);

    assertThat(result.created()).isTrue();
    assertThat(result.operationId()).isNotNull();
    verify(operations).save(any(FinancialOperation.class));
    verify(movements).save(any(Movement.class));
    verify(idempotency).save(any(WithdrawalOperationIdempotency.class));
    verify(debits).applyDebit(any(AccountDebitCommand.class));
    var confirmed = org.mockito.ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(confirmed.capture());
    assertThat(confirmed.getValue().action()).isEqualTo("WITHDRAWAL_CONFIRMED");
    assertThat(confirmed.getValue().result()).isEqualTo("CONFIRMED");
  }

  @Test
  void mapsDebitRejectionsToRejectedWithoutMovement() {
    emptyIdempotency();
    for (var outcome :
        new AccountDebitResult.AccountDebitOutcome[] {
          AccountDebitResult.AccountDebitOutcome.ACCOUNT_NOT_FOUND,
          AccountDebitResult.AccountDebitOutcome.NOT_OPERABLE,
          AccountDebitResult.AccountDebitOutcome.CURRENCY_MISMATCH,
          AccountDebitResult.AccountDebitOutcome.INVALID_AMOUNT,
          AccountDebitResult.AccountDebitOutcome.INSUFFICIENT_FUNDS
        }) {
      when(debits.applyDebit(any(AccountDebitCommand.class)))
          .thenReturn(new AccountDebitResult(outcome, null));

      assertThatThrownBy(() -> service.withdraw(command, "k1", actor))
          .isInstanceOf(WithdrawalOperationException.class)
          .matches(
              e ->
                  ((WithdrawalOperationException) e).getKind()
                      == WithdrawalOperationException.Kind.REJECTED);
    }
    verify(movements, never()).save(any());
    var rejected = org.mockito.ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit, org.mockito.Mockito.times(5)).record(rejected.capture());
    assertThat(rejected.getAllValues())
        .allMatch(entry -> entry.action().equals("WITHDRAWAL_REJECTED"));
  }

  @Test
  void requiresIdempotencyKey() {
    assertThatThrownBy(() -> service.withdraw(command, " ", actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.FAILED);
  }

  @Test
  void replaysSameKeyWithoutNewEffects() {
    String hash = WithdrawalService.requestHash(command);
    UUID operationId = UUID.randomUUID();
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideWithdraw(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new WithdrawalOperationIdempotency(
                    "k1", operationId, hash, WithdrawalOperationOutcome.CONFIRMED)));

    WithdrawalResult result = service.withdraw(command, "k1", actor);

    assertThat(result.created()).isFalse();
    assertThat(result.operationId()).isEqualTo(operationId);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    verify(debits, never()).applyDebit(any());
    verify(audit, never()).record(any());
  }

  @Test
  void replaysRejectionDeterministically() {
    String hash = WithdrawalService.requestHash(command);
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideWithdraw(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new WithdrawalOperationIdempotency(
                    "k1", UUID.randomUUID(), hash, WithdrawalOperationOutcome.REJECTED)));

    assertThatThrownBy(() -> service.withdraw(command, "k1", actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.REJECTED);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    verify(debits, never()).applyDebit(any());
    verify(audit, never()).record(any());
  }

  @Test
  void conflictsOnSameKeyWithDifferentPayload() {
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideWithdraw(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new WithdrawalOperationIdempotency(
                    "k1", UUID.randomUUID(), "other-hash", WithdrawalOperationOutcome.CONFIRMED)));

    assertThatThrownBy(() -> service.withdraw(command, "k1", actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.CONFLICT);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    var conflict = org.mockito.ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(conflict.capture());
    assertThat(conflict.getValue().action()).isEqualTo("WITHDRAWAL_CONFLICT");
  }

  @Test
  void differentKeysAreIndependentWithdrawals() {
    emptyIdempotency();
    appliedDebit();
    when(idempotency.findById("k2")).thenReturn(Optional.empty());

    WithdrawalResult first = service.withdraw(command, "k1", actor);
    WithdrawalResult second = service.withdraw(command, "k2", actor);

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isTrue();
    assertThat(first.operationId()).isNotEqualTo(second.operationId());
  }

  @Test
  void postLockRecheckReplaysCommittedWinnerWithoutWriting() {
    UUID winnerId = UUID.randomUUID();
    String hash = WithdrawalService.requestHash(command);
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideWithdraw(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());
    // Pre-check sees nothing; the post-lock re-check finds the committed winner.
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.empty(),
            Optional.of(
                new WithdrawalOperationIdempotency(
                    "k1", winnerId, hash, WithdrawalOperationOutcome.CONFIRMED)));
    appliedDebit();

    WithdrawalResult replayed = service.withdraw(command, "k1", actor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(winnerId);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void concurrentUnknownOutcomeIsUnknownNeverFailed() {
    emptyIdempotency();
    appliedDebit();
    lenient()
        .doThrow(new DataIntegrityViolationException("duplicate key"))
        .when(operations)
        .flush();

    assertThatThrownBy(() -> service.withdraw(command, "k1", actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.UNKNOWN)
        .hasMessageContaining("same idempotency key");
  }

  @Test
  void arithmeticFailureIsFailedWithoutEffect() {
    emptyIdempotency();
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenThrow(new ArithmeticException("long underflow"));

    assertThatThrownBy(() -> service.withdraw(command, "k1", actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.FAILED);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
  }

  @Test
  void rejectsUnknownAccountWithoutDebitCall() {
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.withdraw(command, "k1", actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.REJECTED);
    verify(debits, never()).applyDebit(any());
    verify(movements, never()).save(any());
  }

  @Test
  void deniesUnauthorizedAndUnauthenticatedActors() {
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideWithdraw(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.withdraw(command, "k1", actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.FORBIDDEN);

    var anonymous = new AuthenticatedActor(null, Set.of());
    when(authorization.decideWithdraw(anonymous, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("unauthenticated actor"));

    assertThatThrownBy(() -> service.withdraw(command, "k1", anonymous))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.UNAUTHENTICATED);
    verify(operations, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void authorizedQueryReturnsStoredOutcome() {
    UUID operationId = UUID.randomUUID();
    String hash = WithdrawalService.requestHash(command);
    var operation =
        new FinancialOperation(
            operationId,
            FinancialOperationType.WITHDRAWAL,
            "holder-1",
            accountId,
            40_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new WithdrawalOperationIdempotency(
                    "k1", operationId, hash, WithdrawalOperationOutcome.CONFIRMED)));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideWithdraw(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());

    var found = service.findAuthorizedByKey("k1", hash, actor);

    assertThat(found).isPresent();
    assertThat(found.get().operationId()).isEqualTo(operationId);
    assertThat(found.get().outcome()).isEqualTo(WithdrawalOperationOutcome.CONFIRMED);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void authorizedQueryReturnsRejectedOutcome() {
    UUID operationId = UUID.randomUUID();
    String hash = WithdrawalService.requestHash(command);
    var operation =
        new FinancialOperation(
            operationId,
            FinancialOperationType.WITHDRAWAL,
            "holder-1",
            accountId,
            40_00L,
            "DOP",
            FinancialOperationStatus.REJECTED);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new WithdrawalOperationIdempotency(
                    "k1", operationId, hash, WithdrawalOperationOutcome.REJECTED)));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideWithdraw(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());

    var found = service.findAuthorizedByKey("k1", hash, actor);

    assertThat(found).isPresent();
    assertThat(found.get().outcome()).isEqualTo(WithdrawalOperationOutcome.REJECTED);
  }

  @Test
  void queryUnknownKeyOrHashMismatchStaysEmpty() {
    when(idempotency.findById("missing")).thenReturn(Optional.empty());

    assertThat(service.findAuthorizedByKey("missing", "hash", actor)).isEmpty();

    UUID operationId = UUID.randomUUID();
    String hash = WithdrawalService.requestHash(command);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new WithdrawalOperationIdempotency(
                    "k1", operationId, hash, WithdrawalOperationOutcome.CONFIRMED)));

    assertThat(service.findAuthorizedByKey("k1", "other-hash", actor)).isEmpty();
  }

  @Test
  void queryDeniedForUnauthorizedActor() {
    UUID operationId = UUID.randomUUID();
    String hash = WithdrawalService.requestHash(command);
    var operation =
        new FinancialOperation(
            operationId,
            FinancialOperationType.WITHDRAWAL,
            "holder-1",
            accountId,
            40_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED);
    var other = new AuthenticatedActor("holder-2", Set.of());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new WithdrawalOperationIdempotency(
                    "k1", operationId, hash, WithdrawalOperationOutcome.CONFIRMED)));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideWithdraw(other, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.findAuthorizedByKey("k1", hash, other))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.FORBIDDEN);
  }
}
