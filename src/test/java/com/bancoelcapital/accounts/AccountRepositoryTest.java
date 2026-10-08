package com.bancoelcapital.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.bancoelcapital.accounts.internal.Account;
import com.bancoelcapital.accounts.internal.AccountCreationIdempotency;
import com.bancoelcapital.accounts.internal.CreationOutcome;

import jakarta.persistence.PersistenceException;

/** Repository slice on H2 (transitory until Docker enables Testcontainers PG). */
@DataJpaTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:accts;MODE=PostgreSQL",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class AccountRepositoryTest {

  @Autowired TestEntityManager entities;

  @Test
  void persistsAccountAndEnforcesKeyUniqueness() {
    UUID id = UUID.randomUUID();
    entities.persist(new Account(id, "holder-1", "DOP", "BASIC"));
    entities.persist(
        new AccountCreationIdempotency("k1", id, "hash", CreationOutcome.CONFIRMED, "holder-1"));
    entities.flush();
    entities.clear();

    assertThat(entities.find(Account.class, id)).isNotNull();
    entities.persist(
        new AccountCreationIdempotency(
            "k1", UUID.randomUUID(), "h", CreationOutcome.CONFIRMED, "holder-1"));
    assertThatThrownBy(entities::flush).isInstanceOf(PersistenceException.class);
  }
}
