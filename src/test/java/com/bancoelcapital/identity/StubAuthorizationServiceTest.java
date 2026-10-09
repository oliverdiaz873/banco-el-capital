package com.bancoelcapital.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class StubAuthorizationServiceTest {

  StubAuthorizationService service = new StubAuthorizationService();

  @Test
  void selfActorIsAllowedToWithdraw() {
    var actor = new AuthenticatedActor("holder-1", Set.of());

    assertThat(service.decideWithdraw(actor, "holder-1").allowed()).isTrue();
  }

  @Test
  void bankEmployeeIsAllowedToWithdrawForAnotherHolder() {
    var employee = new AuthenticatedActor("emp-1", Set.of("BANK_EMPLOYEE"));

    assertThat(service.decideWithdraw(employee, "holder-1").allowed()).isTrue();
  }

  @Test
  void otherHolderIsDeniedWithdraw() {
    var other = new AuthenticatedActor("holder-2", Set.of());

    assertThat(service.decideWithdraw(other, "holder-1").allowed()).isFalse();
  }

  @Test
  void selfActorIsAllowedToTransfer() {
    var actor = new AuthenticatedActor("holder-1", Set.of());

    assertThat(service.decideTransfer(actor, "holder-1").allowed()).isTrue();
  }

  @Test
  void otherHolderIsDeniedTransfer() {
    var other = new AuthenticatedActor("holder-2", Set.of());

    assertThat(service.decideTransfer(other, "holder-1").allowed()).isFalse();
  }

  @Test
  void bankEmployeeIsAllowedToTransitionForAnotherHolder() {
    var employee = new AuthenticatedActor("emp-1", Set.of("BANK_EMPLOYEE"));

    assertThat(service.decideTransition(employee, "holder-1").allowed()).isTrue();
  }

  @Test
  void otherHolderIsDeniedTransitionAndView() {
    var other = new AuthenticatedActor("holder-2", Set.of());

    assertThat(service.decideTransition(other, "holder-1").allowed()).isFalse();
    assertThat(service.decideViewAccount(other, "holder-1").allowed()).isFalse();
  }

  @Test
  void selfActorIsAllowedToViewOwnAccount() {
    var actor = new AuthenticatedActor("holder-1", Set.of());

    assertThat(service.decideViewAccount(actor, "holder-1").allowed()).isTrue();
  }

  @Test
  void missingActorIsDeniedAsUnauthenticated() {
    for (AuthenticatedActor anonymous :
        new AuthenticatedActor[] {
          null, new AuthenticatedActor(null, Set.of()), new AuthenticatedActor("  ", Set.of())
        }) {
      AuthorizationDecision decision = service.decideWithdraw(anonymous, "holder-1");

      assertThat(decision.allowed()).isFalse();
      assertThat(decision.reason()).contains("unauthenticated");
    }
  }
}
