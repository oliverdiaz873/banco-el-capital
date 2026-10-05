package com.bancoelcapital.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bancoelcapital.audit.AuditEntry;
import com.bancoelcapital.audit.AuditRecorder;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationDecision;
import com.bancoelcapital.identity.AuthorizationService;

@ExtendWith(MockitoExtension.class)
class CustomerCreationServiceTest {

  @Mock CustomerRepository customers;
  @Mock CustomerCreationIdempotencyRepository idempotency;
  @Mock AuthorizationService authorization;
  @Mock AuditRecorder audit;

  CustomerCreationService service;

  AuthenticatedActor actor = new AuthenticatedActor("customer-001", Set.of());
  CustomerCreationCommand command = new CustomerCreationCommand("customer-001", "Oliver Diaz");

  @BeforeEach
  void setup() {
    service = new CustomerCreationService(customers, idempotency, authorization, audit);
  }

  @Test
  void createsCustomerWhenAuthorizedAndValid() {
    when(authorization.decideCreateCustomer(actor, "customer-001"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
    when(customers.findById("customer-001")).thenReturn(Optional.empty());

    CustomerResult result = service.create(command, "k1", actor);

    assertThat(result.created()).isTrue();
    assertThat(result.customerId()).isEqualTo("customer-001");
    verify(customers).save(any(Customer.class));
    verify(audit).record(any());
  }

  @Test
  void allowsBankEmployeeToCreateForOtherCustomer() {
    var employee = new AuthenticatedActor("emp-1", Set.of("BANK_EMPLOYEE"));
    when(authorization.decideCreateCustomer(employee, "customer-001"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());
    when(customers.findById("customer-001")).thenReturn(Optional.empty());

    CustomerResult result = service.create(command, "k1", employee);

    assertThat(result.created()).isTrue();
    verify(customers).save(any(Customer.class));
  }

  @Test
  void replaysSameKeyAndPayload() {
    String hash = CustomerCreationService.requestHash(command);
    when(authorization.decideCreateCustomer(actor, "customer-001"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new CustomerCreationIdempotency(
                    "k1", "customer-001", hash, CustomerCreationOutcome.CONFIRMED)));

    CustomerResult result = service.create(command, "k1", actor);

    assertThat(result.created()).isFalse();
    assertThat(result.customerId()).isEqualTo("customer-001");
    verify(customers, never()).save(any());
    verify(audit, never()).record(any());
  }

  @Test
  void conflictsOnSameKeyWithDifferentPayload() {
    when(authorization.decideCreateCustomer(actor, "customer-001"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1"))
        .thenReturn(
            Optional.of(
                new CustomerCreationIdempotency(
                    "k1", "customer-001", "other-hash", CustomerCreationOutcome.CONFIRMED)));

    assertThatThrownBy(() -> service.create(command, "k1", actor))
        .isInstanceOf(CustomerCreationException.class)
        .matches(
            e ->
                ((CustomerCreationException) e).getKind()
                    == CustomerCreationException.Kind.CONFLICT);
  }

  @Test
  void rejectsInvalidCustomerIdWithoutEffect() {
    var bad = new CustomerCreationCommand("bad id!", "Name");
    when(authorization.decideCreateCustomer(actor, "bad id!"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(bad, "k1", actor))
        .isInstanceOf(CustomerCreationException.class)
        .matches(
            e ->
                ((CustomerCreationException) e).getKind()
                    == CustomerCreationException.Kind.REJECTED);
    verify(customers, never()).save(any());
  }

  @Test
  void rejectsBlankDisplayNameWithoutEffect() {
    var bad = new CustomerCreationCommand("customer-001", "   ");
    when(authorization.decideCreateCustomer(actor, "customer-001"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(bad, "k1", actor))
        .isInstanceOf(CustomerCreationException.class)
        .matches(
            e ->
                ((CustomerCreationException) e).getKind()
                    == CustomerCreationException.Kind.REJECTED);
    verify(customers, never()).save(any());
  }

  @Test
  void deniesUnauthorizedActor() {
    when(authorization.decideCreateCustomer(actor, "customer-001"))
        .thenReturn(AuthorizationDecision.deny("actor not authorized for holder"));

    assertThatThrownBy(() -> service.create(command, "k1", actor))
        .isInstanceOf(CustomerCreationException.class)
        .matches(
            e ->
                ((CustomerCreationException) e).getKind()
                    == CustomerCreationException.Kind.FORBIDDEN);
    verify(customers, never()).save(any());
  }

  @Test
  void requiresAuthenticatedActor() {
    var anonymous = new AuthenticatedActor(null, Set.of());
    when(authorization.decideCreateCustomer(anonymous, "customer-001"))
        .thenReturn(AuthorizationDecision.deny("unauthenticated actor"));

    assertThatThrownBy(() -> service.create(command, "k1", anonymous))
        .isInstanceOf(CustomerCreationException.class)
        .matches(
            e ->
                ((CustomerCreationException) e).getKind()
                    == CustomerCreationException.Kind.UNAUTHENTICATED);
  }

  @Test
  void requiresIdempotencyKey() {
    assertThatThrownBy(() -> service.create(command, " ", actor))
        .isInstanceOf(CustomerCreationException.class)
        .matches(
            e ->
                ((CustomerCreationException) e).getKind() == CustomerCreationException.Kind.FAILED);
  }

  @Test
  void reusesExistingCustomerOnDifferentKeyWithSamePayload() {
    when(authorization.decideCreateCustomer(actor, "customer-001"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k2")).thenReturn(Optional.empty());
    when(customers.findById("customer-001"))
        .thenReturn(Optional.of(new Customer("customer-001", "Oliver Diaz", "customer-001")));

    CustomerResult result = service.create(command, "k2", actor);

    assertThat(result.created()).isFalse();
    assertThat(result.customerId()).isEqualTo("customer-001");
    verify(customers, never()).save(any(Customer.class));
    var entries = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entries.capture());
    assertThat(entries.getValue().action()).isEqualTo("CUSTOMER_CREATION_REUSED");
    assertThat(entries.getValue().result()).isEqualTo("CONFIRMED");
  }

  @Test
  void conflictsOnExistingCustomerWithDifferentPayload() {
    var other = new CustomerCreationCommand("customer-001", "Other Name");
    when(authorization.decideCreateCustomer(actor, "customer-001"))
        .thenReturn(AuthorizationDecision.allow());
    when(idempotency.findById("k2")).thenReturn(Optional.empty());
    when(customers.findById("customer-001"))
        .thenReturn(Optional.of(new Customer("customer-001", "Oliver Diaz", "customer-001")));

    assertThatThrownBy(() -> service.create(other, "k2", actor))
        .isInstanceOf(CustomerCreationException.class)
        .matches(
            e ->
                ((CustomerCreationException) e).getKind()
                    == CustomerCreationException.Kind.CONFLICT);
  }
}
