package com.bancoelcapital.beneficiaries.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bancoelcapital.accounts.api.AccountDestinationLookup;
import com.bancoelcapital.audit.api.AuditRecorder;
import com.bancoelcapital.beneficiaries.internal.Beneficiary;
import com.bancoelcapital.identity.AuthenticatedActor;
import com.bancoelcapital.identity.AuthorizationDecision;
import com.bancoelcapital.identity.AuthorizationService;
import com.bancoelcapital.identity.IdentityGateway;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Beneficiary public resolution contract (ADR-21, Phase 1). Proves owner-scoped resolution through
 * the real query path, missing/foreign indistinguishability, read-only behavior observed on
 * persisted state, and participation in the caller's transaction. Authorization semantics
 * themselves are covered by StubAuthorizationServiceTest/BeneficiaryServiceTest; the authorization
 * double here only mirrors self-ownership.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:beneficiaries-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@Transactional
class BeneficiaryResolverTest {

  @Autowired BeneficiaryResolver resolver;

  @PersistenceContext EntityManager entities;

  @Autowired PlatformTransactionManager transactionManager;

  @MockitoBean AccountDestinationLookup destinations;

  @MockitoBean AuthorizationService authorization;

  @MockitoBean IdentityGateway identity;

  @MockitoBean AuditRecorder audit;

  @BeforeEach
  void mirrorSelfOwnership() {
    lenient()
        .when(authorization.decideManageBeneficiary(any(), any()))
        .thenAnswer(
            invocation -> {
              AuthenticatedActor actor = invocation.getArgument(0);
              String owner = invocation.getArgument(1);
              if (actor != null
                  && actor.subject() != null
                  && !actor.subject().isBlank()
                  && actor.subject().equals(owner)) {
                return AuthorizationDecision.allow();
              }
              return AuthorizationDecision.deny("actor not authorized for holder");
            });
  }

  @Test
  void resolvesOwnBeneficiaryWithAllFiveFields() {
    UUID id = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    entities.persist(new Beneficiary(id, "owner-1", destination, "Ahorros", "DOP"));
    entities.flush();

    Optional<BeneficiaryResolution> found = resolver.resolveForOwner(id, "owner-1");

    assertThat(found).isPresent();
    assertThat(found.get().beneficiaryId()).isEqualTo(id);
    assertThat(found.get().ownerCustomerId()).isEqualTo("owner-1");
    assertThat(found.get().destinationAccountId()).isEqualTo(destination);
    assertThat(found.get().destinationCurrency()).isEqualTo("DOP");
    assertThat(found.get().aliasSnapshot()).isEqualTo("Ahorros");
  }

  @Test
  void missingAndForeignAreIndistinguishable() {
    UUID id = UUID.randomUUID();
    entities.persist(new Beneficiary(id, "owner-1", UUID.randomUUID(), "Casa", "DOP"));
    entities.flush();

    assertThat(resolver.resolveForOwner(UUID.randomUUID(), "owner-1")).isEmpty();
    assertThat(resolver.resolveForOwner(id, "owner-2")).isEmpty();
  }

  @Test
  void nullAndBlankInputsReturnEmpty() {
    UUID id = UUID.randomUUID();
    entities.persist(new Beneficiary(id, "owner-1", UUID.randomUUID(), "Casa", "DOP"));
    entities.flush();

    assertThat(resolver.resolveForOwner(null, "owner-1")).isEmpty();
    assertThat(resolver.resolveForOwner(id, null)).isEmpty();
    assertThat(resolver.resolveForOwner(id, "  ")).isEmpty();
  }

  @Test
  void joinsOuterTransactionAndSeesUncommittedRow() {
    UUID id = UUID.randomUUID();
    UUID destination = UUID.randomUUID();

    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    outer.execute(
        status -> {
          entities.persist(new Beneficiary(id, "owner-1", destination, "Ahorros", "DOP"));
          entities.flush();
          Optional<BeneficiaryResolution> inside = resolver.resolveForOwner(id, "owner-1");
          assertThat(inside).isPresent();
          assertThat(inside.get().destinationAccountId()).isEqualTo(destination);
          status.setRollbackOnly();
          return null;
        });

    entities.clear();
    assertThat(resolver.resolveForOwner(id, "owner-1")).isEmpty();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void resolvesWithoutOuterTransaction() {
    UUID id = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              entities.persist(new Beneficiary(id, "owner-1", destination, "Casa", "DOP"));
              entities.flush();
              return null;
            });

    Optional<BeneficiaryResolution> found = resolver.resolveForOwner(id, "owner-1");

    assertThat(found).isPresent();
    assertThat(found.get().destinationAccountId()).isEqualTo(destination);

    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              Beneficiary stored = entities.find(Beneficiary.class, id);
              entities.remove(stored);
              entities.flush();
              return null;
            });
  }

  @Test
  void readsPersistNothing() {
    UUID id = UUID.randomUUID();
    entities.persist(new Beneficiary(id, "owner-1", UUID.randomUUID(), "Casa", "DOP"));
    entities.flush();
    entities.clear();
    long beneficiariesBefore = count("Beneficiary");
    long idempotencyBefore = count("BeneficiaryCreationIdempotency");
    long auditBefore = count("AuditEvent");

    assertThat(resolver.resolveForOwner(id, "owner-1")).isPresent();
    assertThat(resolver.resolveForOwner(UUID.randomUUID(), "owner-1")).isEmpty();
    assertThat(resolver.resolveForOwner(id, "owner-2")).isEmpty();

    assertThat(count("Beneficiary")).isEqualTo(beneficiariesBefore);
    assertThat(count("BeneficiaryCreationIdempotency")).isEqualTo(idempotencyBefore);
    assertThat(count("AuditEvent")).isEqualTo(auditBefore);
  }

  private long count(String entityName) {
    return entities
        .createQuery("select count(e) from " + entityName + " e", Long.class)
        .getSingleResult();
  }
}
