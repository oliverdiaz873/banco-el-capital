package com.bancoelcapital.postgres;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL 16 validation infrastructure (project target engine). Exactly one container is
 * started per JVM (singleton static initializer) and reused by every subclass; Flyway migrates
 * V1-V7 once and JPA validates against the real schema. A per-class {@code @Container} is
 * deliberately not used: under JUnit 5 it creates one container per test class and the instances
 * accumulate until JVM exit. H2 remains the fast default suite: these tests are skipped (not
 * failed) wherever Docker is unavailable, so {@code ./mvnw -B -ntp verify} stays green without
 * PostgreSQL. Run only them with {@code -Dtest='*PostgresTest'} where Docker exists.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
public abstract class AbstractPostgresTest {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  static {
    // Starts at most once per JVM; skipped entirely without Docker (tests are skipped instead by
    // disabledWithoutDocker, so the suppliers below are never evaluated there).
    if (DockerClientFactory.instance().isDockerAvailable()) {
      POSTGRES.start();
    }
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }
}
