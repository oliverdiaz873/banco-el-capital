package com.bancoelcapital.accounts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationDecision;
import com.bancoelcapital.identity.AuthorizationService;
import com.bancoelcapital.identity.IdentityGateway;

@ExtendWith(MockitoExtension.class)
class AccountCreationServiceTest {

  @Mock AccountRepository accounts;
  @Mock AccountCreationIdempotencyRepository idempotency;
  @Mock AuthorizationService authorization;
  @Mock IdentityGateway identity;
  @Mock AuditRecorder audit;

  AccountCreationService service;

  AuthenticatedActor actor = new AuthenticatedActor("holder-1", Set.of());
  AccountCreationCommand command = new AccountCreationCommand("holder-1", "DOP", "BASIC");

  @BeforeEach
  void setup() {
    service = new AccountCreationService(accounts, idempotency, authorization, identity, audit);
  }

  @Test
  void createsAccountWhenAuthorizedAndValid() {
    when(authorization.decideCreateAccount(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
    when(identity.customerExists("holder-1")).thenReturn(true);

    AccountResult result = service.create(command, "k1", actor);

    assertThat(result.created()).isTrue();
    assertThat(result.accountId()).isNotNull();
    verify(accounts).save(any(Account.class));
    verify(audit).record(any());
  }

  @Test
  void replaysSameKeyAndPayload() {
    UUID id = UUID.randomUUID();
    String hash = AccountCreationService.requestHash(command);
    when(authorization.decideCreateAccount(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new AccountCreationIdempotency(
                    "k1", id, hash, CreationOutcome.CONFIRMED, "holder-1")));

    AccountResult result = service.create(command, "k1", actor);

    assertThat(result.created()).isFalse();
    assertThat(result.accountId()).isEqualTo(id);
    verify(accounts, never()).save(any());
  }

  @Test
  void conflictsOnSameKeyWithDifferentPayload() {
    when(authorization.decideCreateAccount(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new AccountCreationIdempotency(
                    "k1", UUID.randomUUID(), "other-hash", CreationOutcome.CONFIRMED, "holder-1")));

    assertThatThrownBy(() -> service.create(command, "k1", actor))
        .isInstanceOf(AccountCreationException.class)
        .matches(
            e ->
                ((AccountCreationException) e).getKind() == AccountCreationException.Kind.CONFLICT);
  }

  @Test
  void rejectsInvalidCurrencyWithoutEffect() {
    var bad = new AccountCreationCommand("holder-1", "do", "BASIC");
    when(authorization.decideCreateAccount(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
    when(identity.customerExists("holder-1")).thenReturn(true);

    assertThatThrownBy(() -> service.create(bad, "k1", actor))
        .isInstanceOf(AccountCreationException.class)
        .matches(
            e ->
                ((AccountCreationException) e).getKind() == AccountCreationException.Kind.REJECTED);
    verify(accounts, never()).save(any());
  }

  @Test
  void deniesUnauthorizedActor() {
    when(authorization.decideCreateAccount(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.create(command, "k1", actor))
        .isInstanceOf(AccountCreationException.class)
        .matches(
            e ->
                ((AccountCreationException) e).getKind()
                    == AccountCreationException.Kind.FORBIDDEN);
    verify(accounts, never()).save(any());
  }

  @Test
  void authorizedQueryReturnsStoredAccount() {
    UUID id = UUID.randomUUID();
    String hash = AccountCreationService.requestHash(command);
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new AccountCreationIdempotency(
                    "k1", id, hash, CreationOutcome.CONFIRMED, "holder-1")));
    when(authorization.decideCreateAccount(actor, "holder-1"))
        .thenReturn(AuthorizationDecision.allow());

    var found = service.findAuthorizedByKey("k1", hash, actor);

    assertThat(found).isPresent();
    assertThat(found.get().accountId()).isEqualTo(id);
  }

  @Test
  void queryDeniedForOtherHolder() {
    String hash = AccountCreationService.requestHash(command);
    var other = new AuthenticatedActor("holder-2", Set.of());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new AccountCreationIdempotency(
                    "k1", UUID.randomUUID(), hash, CreationOutcome.CONFIRMED, "holder-1")));
    when(authorization.decideCreateAccount(other, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.findAuthorizedByKey("k1", hash, other))
        .isInstanceOf(AccountCreationException.class)
        .matches(
            e ->
                ((AccountCreationException) e).getKind()
                    == AccountCreationException.Kind.FORBIDDEN);
  }

  @Test
  void queryRequiresAuthenticatedActor() {
    String hash = AccountCreationService.requestHash(command);
    var anonymous = new AuthenticatedActor(null, Set.of());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new AccountCreationIdempotency(
                    "k1", UUID.randomUUID(), hash, CreationOutcome.CONFIRMED, "holder-1")));
    when(authorization.decideCreateAccount(anonymous, "holder-1"))
        .thenReturn(AuthorizationDecision.deny("unauthenticated actor"));

    assertThatThrownBy(() -> service.findAuthorizedByKey("k1", hash, anonymous))
        .isInstanceOf(AccountCreationException.class)
        .matches(
            e ->
                ((AccountCreationException) e).getKind()
                    == AccountCreationException.Kind.UNAUTHENTICATED);
  }

  @Test
  void queryUnknownKeyStaysEmpty() {
    when(idempotency.findById("missing")).thenReturn(Optional.empty());

    assertThat(service.findAuthorizedByKey("missing", "hash", actor)).isEmpty();
  }
}
