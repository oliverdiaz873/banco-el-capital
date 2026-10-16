package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import com.bancoelcapital.beneficiaries.api.BeneficiaryResolution;
import com.bancoelcapital.beneficiaries.api.BeneficiaryResolver;
import com.bancoelcapital.financialops.core.FinancialOperationRepository;
import com.bancoelcapital.financialops.core.MovementRepository;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationDecision;
import com.bancoelcapital.identity.AuthorizationService;

/**
 * P1a: beneficiary resolution and dual authorization with mocked collaborators. Missing and foreign
 * beneficiaries are indistinguishable; the destination always comes from the server-side
 * resolution, never from the client.
 */
@ExtendWith(MockitoExtension.class)
class TransferBeneficiaryServiceTest {

  @Mock FinancialOperationRepository operations;
  @Mock MovementRepository movements;
  @Mock TransferOperationIdempotencyRepository idempotency;
  @Mock AccountLookupService lookup;
  @Mock AccountCreditService credits;
  @Mock AccountDebitService debits;
  @Mock AuthorizationService authorization;
  @Mock AuditRecorder audit;
  @Mock BeneficiaryResolver beneficiaries;
  @Mock TransferBeneficiaryUsageRepository usage;
  @Mock PlatformTransactionManager transactionManager;

  TransferService service;

  UUID sourceId = new UUID(0L, 1L);
  UUID resolvedId = new UUID(0L, 2L);
  UUID beneficiaryId = new UUID(0L, 3L);
  AuthenticatedActor actor = new AuthenticatedActor("holder-source", Set.of());

  BeneficiaryResolution resolution =
      new BeneficiaryResolution(beneficiaryId, "holder-source", resolvedId, "DOP", "Ahorros");

  @BeforeEach
  void setup() {
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
            beneficiaries,
            usage,
            transactionManager);
  }

  private TransferCommand command() {
    return new TransferCommand(sourceId, null, beneficiaryId, 10_00L, "DOP");
  }

  private void sourceAuthorized() {
    when(lookup.findHolderCustomerId(sourceId)).thenReturn(Optional.of("holder-source"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
  }

  private void resolvedOwned() {
    when(beneficiaries.resolveForOwner(beneficiaryId, "holder-source"))
        .thenReturn(Optional.of(resolution));
    when(authorization.decideManageBeneficiary(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
  }

  private void appliedLegs() {
    when(debits.applyDebit(any(AccountDebitCommand.class)))
        .thenReturn(new AccountDebitResult(AccountDebitResult.AccountDebitOutcome.APPLIED, 90_00L));
    when(credits.applyCredit(any(AccountCreditCommand.class)))
        .thenReturn(
            new AccountCreditResult(AccountCreditResult.AccountCreditOutcome.APPLIED, 10_00L));
  }

  @Test
  void confirmsBeneficiaryTransferWithResolvedDestination() {
    sourceAuthorized();
    resolvedOwned();
    appliedLegs();

    TransferResult result = service.transfer(command(), "k1", actor);

    assertThat(result.created()).isTrue();
    var credit = ArgumentCaptor.forClass(AccountCreditCommand.class);
    verify(credits).applyCredit(credit.capture());
    assertThat(credit.getValue().accountId()).isEqualTo(resolvedId);
    var intent = ArgumentCaptor.forClass(TransferOperationIdempotency.class);
    verify(idempotency).save(intent.capture());
    assertThat(intent.getValue().getBeneficiaryId()).isEqualTo(beneficiaryId);
    assertThat(intent.getValue().getPath()).isEqualTo("beneficiary-transfer");
    assertThat(intent.getValue().getRequestHash())
        .isEqualTo(TransferService.requestHash(command()));
    var confirmed = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(confirmed.capture());
    assertThat(confirmed.getValue().action()).isEqualTo("TRANSFER_CONFIRMED");
    var storedUsage = ArgumentCaptor.forClass(TransferBeneficiaryUsage.class);
    verify(usage).save(storedUsage.capture());
    assertThat(storedUsage.getValue().getBeneficiaryId()).isEqualTo(beneficiaryId);
    assertThat(storedUsage.getValue().getBeneficiaryOwner()).isEqualTo("holder-source");
    assertThat(storedUsage.getValue().getBeneficiaryAlias()).isEqualTo("Ahorros");
    assertThat(confirmed.getValue().details()).contains(beneficiaryId.toString());
    assertThat(confirmed.getValue().details()).contains("holder-source");
    assertThat(confirmed.getValue().details()).contains("Ahorros");
  }

  @Test
  void missingBeneficiaryRejectsGenerically() {
    sourceAuthorized();
    when(beneficiaries.resolveForOwner(beneficiaryId, "holder-source"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.transfer(command(), "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                        == TransferOperationException.Kind.REJECTED
                    && e.getMessage().equals("invalid beneficiary"));

    verify(debits, never()).applyDebit(any(AccountDebitCommand.class));
    verify(credits, never()).applyCredit(any(AccountCreditCommand.class));
    verify(movements, never()).save(any());
    verify(usage, never()).save(any());
    var intent = ArgumentCaptor.forClass(TransferOperationIdempotency.class);
    verify(idempotency).save(intent.capture());
    assertThat(intent.getValue().getBeneficiaryId()).isEqualTo(beneficiaryId);
    assertThat(intent.getValue().getPath()).isEqualTo("beneficiary-transfer");
    var rejected = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(rejected.capture());
    assertThat(rejected.getValue().action()).isEqualTo("TRANSFER_REJECTED");
    assertThat(rejected.getValue().details()).isEqualTo("invalid beneficiary");
  }

  @Test
  void foreignBeneficiaryIsIndistinguishableFromMissing() {
    sourceAuthorized();
    when(beneficiaries.resolveForOwner(beneficiaryId, "holder-source"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.transfer(command(), "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                        == TransferOperationException.Kind.REJECTED
                    && e.getMessage().equals("invalid beneficiary"));

    verify(debits, never()).applyDebit(any(AccountDebitCommand.class));
    verify(credits, never()).applyCredit(any(AccountCreditCommand.class));
  }

  @Test
  void manageDeniedPersistsNothing() {
    sourceAuthorized();
    when(beneficiaries.resolveForOwner(beneficiaryId, "holder-source"))
        .thenReturn(Optional.of(resolution));
    when(authorization.decideManageBeneficiary(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.transfer(command(), "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    verify(operations, never()).save(any());
    verify(movements, never()).save(any());
    verify(idempotency, never()).save(any());
    verify(usage, never()).save(any());
    verify(audit, never()).record(any());
    verify(debits, never()).applyDebit(any(AccountDebitCommand.class));
    verify(credits, never()).applyCredit(any(AccountCreditCommand.class));
  }

  @Test
  void clientSuppliedDestinationIsIgnored() {
    sourceAuthorized();
    resolvedOwned();
    appliedLegs();
    UUID decoy = new UUID(0L, 9L);
    TransferCommand spoofed = new TransferCommand(sourceId, decoy, beneficiaryId, 10_00L, "DOP");

    service.transfer(spoofed, "k1", actor);

    var credit = ArgumentCaptor.forClass(AccountCreditCommand.class);
    verify(credits).applyCredit(credit.capture());
    assertThat(credit.getValue().accountId()).isEqualTo(resolvedId);
  }

  @Test
  void resolvedSameAccountRejects() {
    sourceAuthorized();
    BeneficiaryResolution self =
        new BeneficiaryResolution(beneficiaryId, "holder-source", sourceId, "DOP", "Propia");
    when(beneficiaries.resolveForOwner(beneficiaryId, "holder-source"))
        .thenReturn(Optional.of(self));
    when(authorization.decideManageBeneficiary(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());

    assertThatThrownBy(() -> service.transfer(command(), "k1", actor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    verify(debits, never()).applyDebit(any(AccountDebitCommand.class));
    verify(credits, never()).applyCredit(any(AccountCreditCommand.class));
    verify(movements, never()).save(any());
    verify(usage, never()).save(any());
  }

  @Test
  void locksLegsInUuidOrderOfResolvedPair() {
    UUID bigSource = new UUID(0L, 9L);
    UUID smallDestination = new UUID(0L, 1L);
    TransferCommand command = new TransferCommand(bigSource, null, beneficiaryId, 10_00L, "DOP");
    BeneficiaryResolution small =
        new BeneficiaryResolution(
            beneficiaryId, "holder-source", smallDestination, "DOP", "Ahorros");
    when(lookup.findHolderCustomerId(bigSource)).thenReturn(Optional.of("holder-source"));
    when(authorization.decideTransfer(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
    when(beneficiaries.resolveForOwner(beneficiaryId, "holder-source"))
        .thenReturn(Optional.of(small));
    when(authorization.decideManageBeneficiary(actor, "holder-source"))
        .thenReturn(AuthorizationDecision.allow());
    appliedLegs();

    service.transfer(command, "k1", actor);

    InOrder order = inOrder(credits, debits);
    order.verify(credits).applyCredit(any(AccountCreditCommand.class));
    order.verify(debits).applyDebit(any(AccountDebitCommand.class));
  }
}
