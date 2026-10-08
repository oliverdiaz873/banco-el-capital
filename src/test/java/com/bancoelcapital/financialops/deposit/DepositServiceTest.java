package com.bancoelcapital.financialops.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.bancoelcapital.accounts.api.AccountCreditCommand;
import com.bancoelcapital.accounts.api.AccountCreditResult;
import com.bancoelcapital.accounts.api.AccountCreditService;
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
class DepositServiceTest {

  @Mock FinancialOperationRepository operations;
  @Mock MovementRepository movements;
  @Mock DepositOperationIdempotencyRepository idempotency;
  @Mock AccountLookupService lookup;
  @Mock AccountCreditService credits;
  @Mock AuthorizationService authorization;
  @Mock AuditRecorder audit;
  @Mock PlatformTransactionManager transactionManager;

  DepositService service;

  UUID accountId = UUID.randomUUID();
  AuthenticatedActor actor = new AuthenticatedActor("holder-1", Set.of());
  DepositCommand command = new DepositCommand(accountId, 10_00L, "DOP");

  @BeforeEach
  void setup() {
    // Lenient: not every test reaches a transaction (auth/key/replay short-circuits).
    lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(mock(TransactionStatus.class));
    service =
        new DepositService(
            operations,
            movements,
            idempotency,
            lookup,
            credits,
            authorization,
            audit,
            transactionManager);
  }

  private void authorizedValidSetup() {
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideDeposit(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
  }

  private void appliedCredit() {
    when(credits.applyCredit(any(AccountCreditCommand.class)))
        .thenReturn(
            new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.APPLIED, 10_00L));
  }

  @Test
  void confirmsDepositWithSingleOperationMovementAndBalanceEffect() {
    authorizedValidSetup();
    appliedCredit();

    DepositResult result = service.deposit(command, "k1", actor);

    assertThat(result.created()).isTrue();
    assertThat(result.operationId()).isNotNull();
    verify(operations).save(any(FinancialOperation.class));
    verify(movements).save(any(Movement.class));
    verify(idempotency).save(any(DepositOperationIdempotency.class));
    verify(credits).applyCredit(any(AccountCreditCommand.class));
    var confirmed = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(confirmed.capture());
    assertThat(confirmed.getValue().action()).isEqualTo("DEPOSIT_CONFIRMED");
    assertThat(confirmed.getValue().result()).isEqualTo("CONFIRMED");
  }

  @Test
  void rejectsUnknownAccountWithoutFinancialEffect() {
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deposit(command, "k1", actor))
        .isInstanceOf(DepositOperationException.class)
        .matches(
            e ->
                ((DepositOperationException) e).getKind()
                    == DepositOperationException.Kind.REJECTED);
    verify(movements, never()).save(any());
    verify(credits, never()).applyCredit(any());
    var rejected = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(rejected.capture());
    assertThat(rejected.getValue().action()).isEqualTo("DEPOSIT_REJECTED");
  }

  @Test
  void mapsCreditRejectionsToRejectedWithoutMovement() {
    authorizedValidSetup();
    for (var outcome :
        new AccountCreditResult.AccountCreditOutcome[] {
          AccountCreditResult.AccountCreditOutcome.ACCOUNT_NOT_FOUND,
          AccountCreditResult.AccountCreditOutcome.NOT_OPERABLE,
          AccountCreditResult.AccountCreditOutcome.CURRENCY_MISMATCH,
          AccountCreditResult.AccountCreditOutcome.INVALID_AMOUNT
        }) {
      when(credits.applyCredit(any(AccountCreditCommand.class)))
          .thenReturn(new AccountCreditResult(outcome, null));

      assertThatThrownBy(() -> service.deposit(command, "k1", actor))
          .isInstanceOf(DepositOperationException.class)
          .matches(
              e ->
                  ((DepositOperationException) e).getKind()
                      == DepositOperationException.Kind.REJECTED);
    }
    verify(movements, never()).save(any());
    var rejected = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit, times(4)).record(rejected.capture());
    assertThat(rejected.getAllValues())
        .allMatch(entry -> entry.action().equals("DEPOSIT_REJECTED"));
  }

  @Test
  void deniesUnauthorizedAndUnauthenticatedActors() {
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideDeposit(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.deposit(command, "k1", actor))
        .isInstanceOf(DepositOperationException.class)
        .matches(
            e ->
                ((DepositOperationException) e).getKind()
                    == DepositOperationException.Kind.FORBIDDEN);

    var anonymous = new AuthenticatedActor(null, Set.of());
    when(authorization.decideDeposit(anonymous, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("unauthenticated actor"));

    assertThatThrownBy(() -> service.deposit(command, "k1", anonymous))
        .isInstanceOf(DepositOperationException.class)
        .matches(
            e ->
                ((DepositOperationException) e).getKind()
                    == DepositOperationException.Kind.UNAUTHENTICATED);
    verify(operations, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void requiresIdempotencyKey() {
    assertThatThrownBy(() -> service.deposit(command, " ", actor))
        .isInstanceOf(DepositOperationException.class)
        .matches(
            e ->
                ((DepositOperationException) e).getKind() == DepositOperationException.Kind.FAILED);
  }

  @Test
  void replaysSameKeyWithoutNewEffects() {
    String hash = DepositService.requestHash(command);
    UUID operationId = UUID.randomUUID();
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideDeposit(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new DepositOperationIdempotency(
                    "k1", operationId, hash, DepositOperationOutcome.CONFIRMED)));

    DepositResult result = service.deposit(command, "k1", actor);

    assertThat(result.created()).isFalse();
    assertThat(result.operationId()).isEqualTo(operationId);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    verify(credits, never()).applyCredit(any());
    verify(audit, never()).record(any());
  }

  @Test
  void conflictsOnSameKeyWithDifferentPayload() {
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideDeposit(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new DepositOperationIdempotency(
                    "k1", UUID.randomUUID(), "other-hash", DepositOperationOutcome.CONFIRMED)));

    assertThatThrownBy(() -> service.deposit(command, "k1", actor))
        .isInstanceOf(DepositOperationException.class)
        .matches(
            e ->
                ((DepositOperationException) e).getKind()
                    == DepositOperationException.Kind.CONFLICT);
    var conflict = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(conflict.capture());
    assertThat(conflict.getValue().action()).isEqualTo("DEPOSIT_CONFLICT");
  }

  @Test
  void differentKeysAreIndependentDeposits() {
    authorizedValidSetup();
    appliedCredit();
    when(idempotency.findById("k2")).thenReturn(Optional.empty());

    DepositResult first = service.deposit(command, "k1", actor);
    DepositResult second = service.deposit(command, "k2", actor);

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isTrue();
    assertThat(first.operationId()).isNotEqualTo(second.operationId());
  }

  @Test
  void postLockRecheckReplaysCommittedWinnerWithoutWriting() {
    UUID winnerId = UUID.randomUUID();
    String hash = DepositService.requestHash(command);
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideDeposit(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());
    // Pre-check sees nothing; the post-lock re-check finds the committed winner.
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.empty(),
            Optional.of(
                new DepositOperationIdempotency(
                    "k1", winnerId, hash, DepositOperationOutcome.CONFIRMED)));
    appliedCredit();

    DepositResult replayed = service.deposit(command, "k1", actor);

    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(winnerId);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
  }

  @Test
  void concurrentUnknownOutcomeIsUnknownNeverFailed() {
    authorizedValidSetup();
    appliedCredit();
    doThrow(new DataIntegrityViolationException("duplicate key")).when(operations).flush();
    when(idempotency.findById("k1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deposit(command, "k1", actor))
        .isInstanceOf(DepositOperationException.class)
        .matches(
            e ->
                ((DepositOperationException) e).getKind() == DepositOperationException.Kind.UNKNOWN)
        .hasMessageContaining("same idempotency key");
  }

  @Test
  void arithmeticOverflowIsFailedWithoutEffect() {
    authorizedValidSetup();
    when(credits.applyCredit(any(AccountCreditCommand.class)))
        .thenThrow(new ArithmeticException("long overflow"));

    assertThatThrownBy(() -> service.deposit(command, "k1", actor))
        .isInstanceOf(DepositOperationException.class)
        .matches(
            e ->
                ((DepositOperationException) e).getKind() == DepositOperationException.Kind.FAILED);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
  }

  @Test
  void authorizedQueryReturnsStoredOutcome() {
    UUID operationId = UUID.randomUUID();
    String hash = DepositService.requestHash(command);
    var operation =
        new FinancialOperation(
            operationId,
            FinancialOperationType.DEPOSIT,
            "holder-1",
            accountId,
            10_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new DepositOperationIdempotency(
                    "k1", operationId, hash, DepositOperationOutcome.CONFIRMED)));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideDeposit(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());

    var found = service.findAuthorizedByKey("k1", hash, actor);

    assertThat(found).isPresent();
    assertThat(found.get().operationId()).isEqualTo(operationId);
    assertThat(found.get().outcome()).isEqualTo(DepositOperationOutcome.CONFIRMED);
    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void authorizedQueryReturnsRejectedOutcome() {
    UUID operationId = UUID.randomUUID();
    String hash = DepositService.requestHash(command);
    var operation =
        new FinancialOperation(
            operationId,
            FinancialOperationType.DEPOSIT,
            "holder-1",
            accountId,
            10_00L,
            "DOP",
            FinancialOperationStatus.REJECTED);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new DepositOperationIdempotency(
                    "k1", operationId, hash, DepositOperationOutcome.REJECTED)));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideDeposit(actor, "holder-1")).thenReturn(AuthorizationDecision.allow());

    var found = service.findAuthorizedByKey("k1", hash, actor);

    assertThat(found).isPresent();
    assertThat(found.get().outcome()).isEqualTo(DepositOperationOutcome.REJECTED);
  }

  @Test
  void queryUnknownKeyOrHashMismatchStaysEmpty() {
    when(idempotency.findById("missing")).thenReturn(Optional.empty());

    assertThat(service.findAuthorizedByKey("missing", "hash", actor)).isEmpty();

    UUID operationId = UUID.randomUUID();
    String hash = DepositService.requestHash(command);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new DepositOperationIdempotency(
                    "k1", operationId, hash, DepositOperationOutcome.CONFIRMED)));

    assertThat(service.findAuthorizedByKey("k1", "other-hash", actor)).isEmpty();
  }

  @Test
  void queryDeniedForUnauthorizedActor() {
    UUID operationId = UUID.randomUUID();
    String hash = DepositService.requestHash(command);
    var operation =
        new FinancialOperation(
            operationId,
            FinancialOperationType.DEPOSIT,
            "holder-1",
            accountId,
            10_00L,
            "DOP",
            FinancialOperationStatus.CONFIRMED);
    var other = new AuthenticatedActor("holder-2", Set.of());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new DepositOperationIdempotency(
                    "k1", operationId, hash, DepositOperationOutcome.CONFIRMED)));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));
    when(lookup.findHolderCustomerId(accountId)).thenReturn(Optional.of("holder-1"));
    when(authorization.decideDeposit(other, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.findAuthorizedByKey("k1", hash, other))
        .isInstanceOf(DepositOperationException.class)
        .matches(
            e ->
                ((DepositOperationException) e).getKind()
                    == DepositOperationException.Kind.FORBIDDEN);
  }
}
