package com.bancoelcapital.beneficiaries.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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

import com.bancoelcapital.accounts.api.AccountDestinationLookup;
import com.bancoelcapital.accounts.api.DestinationCurrency;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationDecision;
import com.bancoelcapital.identity.AuthorizationService;
import com.bancoelcapital.identity.IdentityGateway;

@ExtendWith(MockitoExtension.class)
class BeneficiaryServiceTest {

  @Mock BeneficiaryRepository beneficiaries;
  @Mock BeneficiaryCreationIdempotencyRepository idempotency;
  @Mock AccountDestinationLookup destinations;
  @Mock AuthorizationService authorization;
  @Mock IdentityGateway identity;
  @Mock AuditRecorder audit;
  @Mock PlatformTransactionManager transactionManager;

  BeneficiaryService service;

  UUID destinationId = new UUID(0L, 7L);
  AuthenticatedActor actor = new AuthenticatedActor("owner-1", Set.of());
  BeneficiaryCommand command = new BeneficiaryCommand(destinationId, "  Ahorros  ");

  @BeforeEach
  void setup() {
    // Lenient: not every test reaches a transaction (auth/key short-circuits).
    lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(mock(TransactionStatus.class));
    service =
        new BeneficiaryService(
            beneficiaries,
            idempotency,
            destinations,
            authorization,
            identity,
            audit,
            transactionManager);
  }

  private void authorizedOwner() {
    when(authorization.decideManageBeneficiary(actor, "owner-1"))
        .thenReturn(AuthorizationDecision.allow());
    when(identity.customerExists("owner-1")).thenReturn(true);
  }

  private void authorizedActorOnly() {
    when(authorization.decideManageBeneficiary(actor, "owner-1"))
        .thenReturn(AuthorizationDecision.allow());
  }

  @Test
  void missingKeyFailsWithoutPersisting() {
    // The key check runs before authorization: no stub is consumed here.

    assertThatThrownBy(() -> service.create(command, " ", actor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.FAILED);
    verify(beneficiaries, never()).save(any());
    verify(idempotency, never()).save(any());
  }

  @Test
  void unauthenticatedActorPersistsNothing() {
    when(authorization.decideManageBeneficiary(actor, "owner-1"))
        .thenReturn(AuthorizationDecision.deny("unauthenticated actor"));

    assertThatThrownBy(() -> service.create(command, "k1", actor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(
            e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.UNAUTHENTICATED);
    verify(beneficiaries, never()).save(any());
    verify(idempotency, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void forbiddenActorPersistsNothing() {
    when(authorization.decideManageBeneficiary(actor, "owner-1"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.create(command, "k1", actor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.FORBIDDEN);
    verify(idempotency, never()).save(any());
  }

  @Test
  void createsBeneficiaryWithTrimmedAlias() {
    authorizedOwner();
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
    when(destinations.findDestination(destinationId))
        .thenReturn(Optional.of(new DestinationCurrency("DOP")));

    BeneficiaryResult result = service.create(command, "k1", actor);

    assertThat(result.created()).isTrue();
    assertThat(result.beneficiaryId()).isNotNull();
    verify(beneficiaries).save(any(Beneficiary.class));
    verify(idempotency).save(any(BeneficiaryCreationIdempotency.class));
  }

  @Test
  void sameKeySamePayloadReplaysWithoutSecondEffect() {
    authorizedActorOnly();
    UUID storedId = UUID.randomUUID();
    String hash = BeneficiaryService.requestHash("owner-1", command);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new BeneficiaryCreationIdempotency(
                    "k1", storedId, hash, BeneficiaryCreationOutcome.CONFIRMED, "owner-1")));

    BeneficiaryResult result = service.create(command, "k1", actor);

    assertThat(result.created()).isFalse();
    assertThat(result.beneficiaryId()).isEqualTo(storedId);
    verify(beneficiaries, never()).save(any());
  }

  @Test
  void sameKeyDifferentPayloadConflicts() {
    authorizedActorOnly();
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new BeneficiaryCreationIdempotency(
                    "k1",
                    UUID.randomUUID(),
                    "other-hash",
                    BeneficiaryCreationOutcome.CONFIRMED,
                    "owner-1")));

    assertThatThrownBy(() -> service.create(command, "k1", actor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.CONFLICT);
    verify(beneficiaries, never()).save(any());
    verify(audit).record(any());
  }

  @Test
  void unknownDestinationRejectsAndPersistsRejection() {
    authorizedOwner();
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
    when(destinations.findDestination(destinationId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(command, "k1", actor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.REJECTED);
    verify(beneficiaries, never()).save(any(Beneficiary.class));
    verify(idempotency).save(any(BeneficiaryCreationIdempotency.class));
  }

  @Test
  void duplicateDestinationUnderAnotherKeyRejectsUnderItsOwnKey() {
    authorizedOwner();
    when(idempotency.findById("k2")).thenReturn(Optional.empty());
    when(destinations.findDestination(destinationId))
        .thenReturn(Optional.of(new DestinationCurrency("DOP")));
    when(beneficiaries.save(any(Beneficiary.class)))
        .thenThrow(new DataIntegrityViolationException("uq_beneficiary_owner_destination"));
    when(beneficiaries.existsByOwnerCustomerIdAndDestinationAccountId("owner-1", destinationId))
        .thenReturn(true);

    assertThatThrownBy(() -> service.create(command, "k2", actor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.REJECTED);
  }

  @Test
  void uniquenessRaceWithoutWinnerIsUncertain() {
    authorizedOwner();
    when(idempotency.findById("k3")).thenReturn(Optional.empty());
    when(destinations.findDestination(destinationId))
        .thenReturn(Optional.of(new DestinationCurrency("DOP")));
    when(beneficiaries.save(any(Beneficiary.class)))
        .thenThrow(new DataIntegrityViolationException("uq_beneficiary_owner_destination"));
    when(beneficiaries.existsByOwnerCustomerIdAndDestinationAccountId("owner-1", destinationId))
        .thenReturn(false);

    assertThatThrownBy(() -> service.create(command, "k3", actor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.UNKNOWN);
  }

  @Test
  void removeDeletesAndAudits() {
    when(authorization.decideManageBeneficiary(actor, "owner-1"))
        .thenReturn(AuthorizationDecision.allow());
    UUID id = UUID.randomUUID();
    when(beneficiaries.findByIdAndOwnerCustomerId(id, "owner-1"))
        .thenReturn(Optional.of(new Beneficiary(id, "owner-1", destinationId, "Ahorros", "DOP")));

    assertThat(service.remove(id, actor)).isTrue();
    verify(beneficiaries).delete(any(Beneficiary.class));
    verify(audit).record(any());
  }

  @Test
  void removeMissingReturnsFalse() {
    when(authorization.decideManageBeneficiary(actor, "owner-1"))
        .thenReturn(AuthorizationDecision.allow());
    UUID id = UUID.randomUUID();
    when(beneficiaries.findByIdAndOwnerCustomerId(id, "owner-1")).thenReturn(Optional.empty());

    assertThat(service.remove(id, actor)).isFalse();
  }

  @Test
  void readsAreOwnerScoped() {
    when(authorization.decideManageBeneficiary(actor, "owner-1"))
        .thenReturn(AuthorizationDecision.allow());
    UUID id = UUID.randomUUID();
    when(beneficiaries.findByIdAndOwnerCustomerId(id, "owner-1")).thenReturn(Optional.empty());
    when(beneficiaries.findByOwnerCustomerIdOrderByCreatedAtAsc("owner-1")).thenReturn(List.of());

    assertThat(service.findForOwner(id, actor)).isEmpty();
    assertThat(service.listForOwner(actor)).isEmpty();
  }

  @Test
  void hashUsesNormalizedAlias() {
    BeneficiaryCommand padded = new BeneficiaryCommand(destinationId, "  Ahorros  ");
    BeneficiaryCommand trimmed = new BeneficiaryCommand(destinationId, "Ahorros");
    BeneficiaryCommand cased = new BeneficiaryCommand(destinationId, "ahorros");

    assertThat(BeneficiaryService.requestHash("owner-1", padded))
        .isEqualTo(BeneficiaryService.requestHash("owner-1", trimmed));
    assertThat(BeneficiaryService.requestHash("owner-1", trimmed))
        .isNotEqualTo(BeneficiaryService.requestHash("owner-1", cased));
    assertThat(BeneficiaryService.normalizeAlias("  Ahorros  ")).isEqualTo("Ahorros");
    assertThat(BeneficiaryService.normalizeAlias("   ")).isNull();
    assertThat(BeneficiaryService.normalizeAlias("x".repeat(65))).isNull();
    assertThat(BeneficiaryService.normalizeAlias("a\tb")).isNull();
  }
}
