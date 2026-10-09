package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.bancoelcapital.accounts.api.AccountCreditCommand;
import com.bancoelcapital.accounts.api.AccountCreditResult;
import com.bancoelcapital.accounts.api.AccountCreditService;
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
class TransferServiceTest {

  @Mock FinancialOperationRepository operations;
  @Mock MovementRepository movements;
  @Mock TransferOperationIdempotencyRepository idempotency;
  @Mock AccountLookupService lookup;
  @Mock AccountCreditService credits;
  @Mock AccountDebitService debits;
  @Mock AuthorizationService authorization;
  @Mock AuditRecorder audit;
  @Mock PlatformTransactionManager transactionManager;

  TransferService service;

  UUID sourceId = new UUID(0L, 1L);
  UUID destinationId = new UUID(0L, 2L);
  AuthenticatedActor actor = new AuthenticatedActor("holder-source", Set.of());
  TransferCommand command = new TransferCommand(sourceId, destinationId, 10_00L, "DOP");

  @BeforeEach
  void setup() {
    // Lenient: not every test reaches a transaction (auth/key/replay short-circuits).
    lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(mock(TransactionStatus.class));
    service =
        new TransferService(
            operations,
            movements,
            idempotency,
            lookup,
            credits,
            debits,
            authorization,
            audit,
            transactionManager);
  }

  private void authorizedValidSetup() {
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
  }

  private void appliedLegs() {
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenReturn(new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.APPLIED, 90_00L));
    when(credits.applyCredit(any(AccountCreditCommand.class)))
        .thenReturn(
            new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.APPLIED, 10_00L));
  }

  @Test
  void confirmsTransferWithOperationTwoMovementsAndBothBalances() {
    authorizedValidSetup();
    appliedLegs();

    TransferResult result = service.transfer(command, "k1", actor);

    assertThat(result.created()).isTrue();
    assertThat(result.operationId()).isNotNull();
    var storedOperation = ArgumentCaptor.forClass(FinancialOperation.class);
    verify(operations).save(storedOperation.capture());
    assertThat(storedOperation.getValue().getOperationType())
        .isEqualTo(FinancialOperationType.TRANSFER);
    // Option A: the operation row identifies the source account.
    assertThat(storedOperation.getValue().getAccountId()).isEqualTo(sourceId);
    verify(movements, times(2)).save(any(Movement.class));
    verify(idempotency).save(any(TransferOperationIdempotency.class));
    verify(debits).applyDebit(any(AccountDebitCommand.class));
    verify(credits).applyCredit(any(AccountCreditCommand.class));
    var confirmed = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(confirmed.capture());
    assertThat(confirmed.getValue().action()).isEqualTo("TRANSFER_CONFIRMED");
    assertThat(confirmed.getValue().result()).isEqualTo("CONFIRMED");
  }

  @Test
  void locksLegsInUuidAscendingOrderSourceFirst() {
    authorizedValidSetup();
    appliedLegs();

    service.transfer(command, "k1", actor);

    InOrder order = inOrder(debits, credits);
    order.verify(debits).applyDebit(any(AccountDebitCommand.class));
    order.verify(credits).applyCredit(any(AccountCreditCommand.class));
  }

  @Test
  void locksLegsInUuidAscendingOrderDestinationFirst() {
    TransferCommand reversed = new TransferCommand(destinationId, sourceId, 10_00L, "DOP");
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    var reversedActor = new AuthenticatedActor("holder-dest", Set.of());
    when(authorization.decideTransfer(reversedActor, "holder-dest"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
    appliedLegs();

    service.transfer(reversed, "k1", reversedActor);

    // destinationId < sourceId, so the credit leg locks first despite being the destination.
    InOrder order = inOrder(debits, credits);
    order.verify(credits).applyCredit(any(AccountCreditCommand.class));
    order.verify(debits).applyDebit(any(AccountDebitCommand.class));
  }

  @Test
  void rejectsSameAccountWithoutFinancialEffect() {
    TransferCommand same = new TransferCommand(sourceId, sourceId, 10_00L, "DOP");

    assertThatThrownBy(() -> service.transfer(same, "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);
    verify(movements, never()).save(any());
    verify(debits, never()).applyDebit(any());
    verify(credits, never()).applyCredit(any());
    var rejected = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(rejected.capture());
    assertThat(rejected.getValue().action()).isEqualTo("TRANSFER_REJECTED");
  }

  @Test
  void rejectsUnknownAccountsWithoutFinancialEffect() {
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.transfer(command, "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);
    verify(movements, never()).save(any());
    verify(debits, never()).applyDebit(any());
    verify(credits, never()).applyCredit(any());
  }

  @Test
  void mapsDebitRejectionsToRejectedWithoutMovement() {
    authorizedValidSetup();
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

      assertThatThrownBy(() -> service.transfer(command, "k1", actor))
          .isInstanceOf(TransferOperationException.class)
          .matches(
              e ->
                  ((TransferOperationException) e).getKind()
                      == TransferOperationException.Kind.REJECTED);
    }
    verify(movements, never()).save(any());
  }

  @Test
  void secondLegRefusalRejectsWithoutConfirmedEffects() {
    authorizedValidSetup();
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenReturn(new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.APPLIED, 90_00L));
    when(credits.applyCredit(any(AccountCreditCommand.class)))
        .thenReturn(
            new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.NOT_OPERABLE, null));

    assertThatThrownBy(() -> service.transfer(command, "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);
    // The rolled-back debit must never surface as a confirmed effect: no movement is
    // persisted; only the deterministic rejection evidence survives.
    verify(movements, never()).save(any(Movement.class));
    var rejected = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(rejected.capture());
    assertThat(rejected.getAllValues())
        .allMatch(entry -> entry.action().equals("TRANSFER_REJECTED"));
  }

  @Test
  void secondLegRefusalReplaysCommittedWinnerInsteadOfPersistingRejection() {
    UUID winnerId = UUID.randomUUID();
    String hash = TransferService.requestHash(command);
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    // Pre-check sees nothing; the second-leg re-check finds the committed winner.
    var winner =
        new TransferOperationIdempotency("k1", winnerId, hash, TransferOperationOutcome.CONFIRMED);
    when(idempotency.findById("k1")).thenReturn(Optional.empty(), Optional.of(winner));
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenReturn(new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.APPLIED, 90_00L));
    when(credits.applyCredit(any(AccountCreditCommand.class)))
        .thenReturn(
            new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.NOT_OPERABLE, null));

    TransferResult replayed = service.transfer(command, "k1", actor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(winnerId);
    verify(movements, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void secondLegRefusalConflictsWhenWinnerHashDiffers() {
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    var winner =
        new TransferOperationIdempotency(
            "k1", UUID.randomUUID(), "other-hash", TransferOperationOutcome.CONFIRMED);
    when(idempotency.findById("k1")).thenReturn(Optional.empty(), Optional.of(winner));
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenReturn(new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.APPLIED, 90_00L));
    when(credits.applyCredit(any(AccountCreditCommand.class)))
        .thenReturn(
            new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.NOT_OPERABLE, null));

    assertThatThrownBy(() -> service.transfer(command, "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);
    verify(movements, never()).save(any());
    var conflict = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(conflict.capture());
    assertThat(conflict.getValue().action()).isEqualTo("TRANSFER_CONFLICT");
  }

  @Test
  void freshRejectionCollisionReplaysLateWinnerInsteadOfLeaking() {
    UUID winnerId = UUID.randomUUID();
    String hash = TransferService.requestHash(command);
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    // Pre-check and second-leg re-check see nothing; the fresh rejection insert then collides
    // because a same-key winner committed in between; the reload finds it.
    var winner =
        new TransferOperationIdempotency("k1", winnerId, hash, TransferOperationOutcome.CONFIRMED);
    when(idempotency.findById("k1"))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenReturn(new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.APPLIED, 90_00L));
    when(credits.applyCredit(any(AccountCreditCommand.class)))
        .thenReturn(
            new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.NOT_OPERABLE, null));
    doThrow(new DataIntegrityViolationException("duplicate key"))
        .when(idempotency)
        .save(any(TransferOperationIdempotency.class));

    TransferResult replayed = service.transfer(command, "k1", actor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(winnerId);
    verify(movements, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void freshRejectionCollisionConflictsWhenLateWinnerHashDiffers() {
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    var winner =
        new TransferOperationIdempotency(
            "k1", UUID.randomUUID(), "other-hash", TransferOperationOutcome.CONFIRMED);
    when(idempotency.findById("k1"))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenReturn(new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.APPLIED, 90_00L));
    when(credits.applyCredit(any(AccountCreditCommand.class)))
        .thenReturn(
            new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.NOT_OPERABLE, null));
    doThrow(new DataIntegrityViolationException("duplicate key"))
        .when(idempotency)
        .save(any(TransferOperationIdempotency.class));

    assertThatThrownBy(() -> service.transfer(command, "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);
    verify(movements, never()).save(any());
    var conflict = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(conflict.capture());
    assertThat(conflict.getValue().action()).isEqualTo("TRANSFER_CONFLICT");
  }

  @Test
  void deniesUnauthorizedAndUnauthenticatedActors() {
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.transfer(command, "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    var anonymous = new AuthenticatedActor(null, Set.of());
    when(authorization.decideTransfer(anonymous, "holder-source"))
        .thenReturn(AuthorizationDecision.deny("unauthenticated actor"));

    assertThatThrownBy(() -> service.transfer(command, "k1", anonymous))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.UNAUTHENTICATED);
    verify(operations, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void requiresIdempotencyKey() {
    assertThatThrownBy(() -> service.transfer(command, " ", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FAILED);
  }

  @Test
  void replaysSameKeyWithoutNewEffects() {
    String hash = TransferService.requestHash(command);
    UUID operationId = UUID.randomUUID();
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new TransferOperationIdempotency(
                    "k1", operationId, hash, TransferOperationOutcome.CONFIRMED)));

    TransferResult result = service.transfer(command, "k1", actor);

    assertThat(result.created()).isFalse();
    assertThat(result.operationId()).isEqualTo(operationId);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    verify(debits, never()).applyDebit(any());
    verify(credits, never()).applyCredit(any());
    verify(audit, never()).record(any());
  }

  @Test
  void conflictsOnSameKeyWithDifferentPayload() {
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new TransferOperationIdempotency(
                    "k1", UUID.randomUUID(), "other-hash", TransferOperationOutcome.CONFIRMED)));

    assertThatThrownBy(() -> service.transfer(command, "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);
    var conflict = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(conflict.capture());
    assertThat(conflict.getValue().action()).isEqualTo("TRANSFER_CONFLICT");
  }

  @Test
  void differentKeysAreIndependentTransfers() {
    authorizedValidSetup();
    appliedLegs();
    when(idempotency.findById("k2")).thenReturn(Optional.empty());

    TransferResult first = service.transfer(command, "k1", actor);
    TransferResult second = service.transfer(command, "k2", actor);

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isTrue();
    assertThat(first.operationId()).isNotEqualTo(second.operationId());
  }

  @Test
  void postLockRecheckReplaysCommittedWinnerWithoutWriting() {
    UUID winnerId = UUID.randomUUID();
    String hash = TransferService.requestHash(command);
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(lookup.findHolderCustomerId(destinationId)).thenReturn(Optional.of("holder-dest"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    // Pre-check sees nothing; the post-lock re-check finds the committed winner.
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.empty(),
            Optional.of(
                new TransferOperationIdempotency(
                    "k1", winnerId, hash, TransferOperationOutcome.CONFIRMED)));
    appliedLegs();

    TransferResult replayed = service.transfer(command, "k1", actor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(winnerId);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
  }

  @Test
  void concurrentUnknownOutcomeIsUnknownNeverFailed() {
    authorizedValidSetup();
    appliedLegs();
    doThrow(new DataIntegrityViolationException("duplicate key")).when(operations).flush();
    when(idempotency.findById("k1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.transfer(command, "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.UNKNOWN)
        .hasMessageContaining("same idempotency key");
  }

  @Test
  void arithmeticOverflowIsFailedWithoutEffect() {
    authorizedValidSetup();
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenThrow(new ArithmeticException("long overflow"));

    assertThatThrownBy(() -> service.transfer(command, "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FAILED);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
  }

  @Test
  void authorizedQueryReturnsStoredOutcome() {
    UUID operationId = UUID.randomUUID();
    String hash = TransferService.requestHash(command);
    var operation =
        new FinancialOperation(
            operationId,
            FinancialOperationType.TRANSFER,
            "holder-source",
            sourceId,
            10_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new TransferOperationIdempotency(
                    "k1", operationId, hash, TransferOperationOutcome.CONFIRMED)));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());

    var found = service.findAuthorizedByKey("k1", hash, actor);

    assertThat(found).isPresent();
    assertThat(found.get().operationId()).isEqualTo(operationId);
    assertThat(found.get().outcome()).isEqualTo(TransferOperationOutcome.CONFIRMED);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void queryUnknownKeyOrHashMismatchStaysEmpty() {
    when(idempotency.findById("missing")).thenReturn(Optional.empty());

    assertThat(service.findAuthorizedByKey("missing", "hash", actor)).isEmpty();

    UUID operationId = UUID.randomUUID();
    String hash = TransferService.requestHash(command);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new TransferOperationIdempotency(
                    "k1", operationId, hash, TransferOperationOutcome.CONFIRMED)));

    assertThat(service.findAuthorizedByKey("k1", "other-hash", actor)).isEmpty();
  }

  @Test
  void queryDeniedForDestinationHolder() {
    UUID operationId = UUID.randomUUID();
    String hash = TransferService.requestHash(command);
    var operation =
        new FinancialOperation(
            operationId,
            FinancialOperationType.TRANSFER,
            "holder-source",
            sourceId,
            10_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED);
    var destinationActor = new AuthenticatedActor("holder-dest", Set.of());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new TransferOperationIdempotency(
                    "k1", operationId, hash, TransferOperationOutcome.CONFIRMED)));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(authorization.decideTransfer(destinationActor, "holder-source"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.findAuthorizedByKey("k1", hash, destinationActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);
  }
}
