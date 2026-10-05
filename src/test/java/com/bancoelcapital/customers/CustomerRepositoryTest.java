package com.bancoelcapital.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.bancoelcapital.identity.IdentityGateway;

import jakarta.persistence.PersistenceException;

/** Repository slice on H2 (transitory until Docker enables Testcontainers PG). */
@DataJpaTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:customers;MODE=PostgreSQL",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@Import(JpaIdentityGateway.class)
class CustomerRepositoryTest {

  @Autowired TestEntityManager entities;

  @Autowired IdentityGateway gateway;

  @Test
  void persistsCustomerAndEnforcesKeyUniqueness() {
    entities.persist(new Customer("customer-001", "Oliver Diaz", "customer-001"));
    entities.persist(
        new CustomerCreationIdempotency(
            "k1", "customer-001", "hash", CustomerCreationOutcome.CONFIRMED));
    entities.flush();
    entities.clear();

    assertThat(entities.find(Customer.class, "customer-001")).isNotNull();
    entities.persist(
        new CustomerCreationIdempotency(
            "k1", "customer-001", "h", CustomerCreationOutcome.CONFIRMED));
    assertThatThrownBy(entities::flush).isInstanceOf(PersistenceException.class);
  }

  @Test
  void gatewayResolvesExistenceFromRepository() {
    assertThat(gateway.customerExists("missing")).isFalse();

    entities.persist(new Customer("customer-001", null, "customer-001"));
    entities.flush();
    entities.clear();

    assertThat(gateway.customerExists("customer-001")).isTrue();
    assertThat(gateway.customerExists(" ")).isFalse();
  }
}
