package com.bancoelcapital.beneficiaries.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import jakarta.persistence.PersistenceException;

/**
 * Beneficiary foundation persistence (ADR-20). Proves the structural model only: beneficiary row
 * with a logical owner/destination reference, uniqueness on (owner, destination) without a unique
 * alias constraint, and a dedicated creation-idempotency namespace. No authorization, audit, API,
 * or disclosure behavior here.
 */
@DataJpaTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:beneficiaries;MODE=PostgreSQL",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class BeneficiaryPersistenceTest {

  @Autowired TestEntityManager entities;

  @Test
  void persistsBeneficiaryWithLogicalReferences() {
    UUID id = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    entities.persist(new Beneficiary(id, "owner-1", destination, "Ahorros", "DOP"));
    entities.persist(
        new BeneficiaryCreationIdempotency(
            "k1", id, "hash", BeneficiaryCreationOutcome.CONFIRMED, "owner-1"));
    entities.flush();
    entities.clear();

    Beneficiary stored = entities.find(Beneficiary.class, id);
    assertThat(stored.getOwnerCustomerId()).isEqualTo("owner-1");
    assertThat(stored.getDestinationAccountId()).isEqualTo(destination);
    assertThat(stored.getAlias()).isEqualTo("Ahorros");
    assertThat(stored.getDestinationCurrency()).isEqualTo("DOP");

    BeneficiaryCreationIdempotency record =
        entities.find(BeneficiaryCreationIdempotency.class, "k1");
    assertThat(record.getOutcome()).isEqualTo(BeneficiaryCreationOutcome.CONFIRMED);
    assertThat(record.getBeneficiaryId()).isEqualTo(id);
  }

  @Test
  void persistsRejectedRowWithoutBeneficiaryReference() {
    entities.persist(
        new BeneficiaryCreationIdempotency(
            "k-rej", null, "hash", BeneficiaryCreationOutcome.REJECTED, "owner-1"));
    entities.flush();
    entities.clear();

    BeneficiaryCreationIdempotency record =
        entities.find(BeneficiaryCreationIdempotency.class, "k-rej");
    assertThat(record.getOutcome()).isEqualTo(BeneficiaryCreationOutcome.REJECTED);
    assertThat(record.getBeneficiaryId()).isNull();
  }

  @Test
  void duplicateOwnerDestinationIsRejectedByTheConstraint() {
    UUID first = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    entities.persist(new Beneficiary(first, "owner-1", destination, "Uno", "DOP"));
    entities.flush();

    entities.persist(new Beneficiary(UUID.randomUUID(), "owner-1", destination, "Dos", "DOP"));
    assertThatThrownBy(entities::flush).isInstanceOf(PersistenceException.class);
  }

  @Test
  void sameAliasOnDifferentDestinationsIsAllowed() {
    entities.persist(
        new Beneficiary(UUID.randomUUID(), "owner-1", UUID.randomUUID(), "Casa", "DOP"));
    entities.persist(
        new Beneficiary(UUID.randomUUID(), "owner-1", UUID.randomUUID(), "Casa", "DOP"));
    entities.flush();

    assertThat(countBeneficiaries()).isEqualTo(2);
  }

  @Test
  void sameDestinationForDifferentOwnersIsAllowed() {
    UUID destination = UUID.randomUUID();
    entities.persist(new Beneficiary(UUID.randomUUID(), "owner-1", destination, "Uno", "DOP"));
    entities.persist(new Beneficiary(UUID.randomUUID(), "owner-2", destination, "Uno", "DOP"));
    entities.flush();

    assertThat(countBeneficiaries()).isEqualTo(2);
  }

  private long countBeneficiaries() {
    return entities
        .getEntityManager()
        .createQuery("select count(b) from Beneficiary b", Long.class)
        .getSingleResult();
  }
}
