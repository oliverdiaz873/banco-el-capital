package com.bancoelcapital.postgres;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL validation infrastructure (project target engine). Exactly one container is
 * started per JVM (singleton static initializer) and reused by every subclass when Docker provides
 * it; Flyway migrates V1 onwards once and JPA validates against the real schema. A per-class
 * {@code @Container} is deliberately not used: under JUnit 5 it creates one container per test
 * class and the instances accumulate until JVM exit. H2 remains the fast default suite: without
 * Docker and without external configuration these tests are skipped (not failed), so {@code ./mvnw
 * -B -ntp verify} stays green without PostgreSQL. Run only them with {@code -Dtest='*Postgres*'}
 * where Docker exists.
 *
 * <p>External PostgreSQL (opt-in, no Docker required): when {@code BANCO_PG_JDBC_URL}, {@code
 * BANCO_PG_USER} and {@code BANCO_PG_PASSWORD} are all set, the gate runs against that database
 * instead of a container. The trio must be complete: a partial configuration fails explicitly
 * (never silently). Credentials are never logged. The external database MUST be a dedicated test
 * database; Flyway migrates it (never cleans), and the gate never drops schema. Testcontainers
 * remains the default and reference path: with no variables set, behavior is exactly as before.
 */
@Testcontainers
@ExtendWith(AbstractPostgresTest.PostgresGateCondition.class)
@SpringBootTest
public abstract class AbstractPostgresTest {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  static {
    // Starts at most once per JVM; skipped entirely without Docker or when an external database
    // is configured (the gate below decides execution; suppliers are evaluated only then).
    if (!isExternalPostgresConfigured() && DockerClientFactory.instance().isDockerAvailable()) {
      POSTGRES.start();
    }
  }

  /**
   * Gate condition for the PostgreSQL suite. Enabled when Docker can provide the container or an
   * external database is fully configured; disabled (skipped, never failed) otherwise. A partial
   * external configuration fails explicitly with a safe message naming only the missing variables.
   */
  static class PostgresGateCondition implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
      // Partial configuration propagates as an explicit failure (never a silent skip).
      requireCompleteExternalConfig();
      if (postgresAvailable()) {
        return ConditionEvaluationResult.enabled(
            "PostgreSQL gate runs against the Testcontainers database or the configured external"
                + " database");
      }
      return ConditionEvaluationResult.disabled(
          "PostgreSQL gate skipped: no Docker and no BANCO_PG_* external configuration");
    }
  }

  static boolean postgresAvailable() {
    return isExternalPostgresConfigured() || DockerClientFactory.instance().isDockerAvailable();
  }

  static boolean isExternalPostgresConfigured() {
    return System.getenv("BANCO_PG_JDBC_URL") != null
        && System.getenv("BANCO_PG_USER") != null
        && System.getenv("BANCO_PG_PASSWORD") != null;
  }

  static void requireCompleteExternalConfig() {
    boolean url = System.getenv("BANCO_PG_JDBC_URL") != null;
    boolean user = System.getenv("BANCO_PG_USER") != null;
    boolean password = System.getenv("BANCO_PG_PASSWORD") != null;
    if ((url || user || password) && !(url && user && password)) {
      StringBuilder missing = new StringBuilder();
      if (!url) {
        missing.append("BANCO_PG_JDBC_URL ");
      }
      if (!user) {
        missing.append("BANCO_PG_USER ");
      }
      if (!password) {
        missing.append("BANCO_PG_PASSWORD ");
      }
      throw new IllegalStateException(
          "Incomplete external PostgreSQL configuration, missing: "
              + missing.toString().trim()
              + ". Set BANCO_PG_JDBC_URL, BANCO_PG_USER and BANCO_PG_PASSWORD together, or none"
              + " (Testcontainers default applies).");
    }
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    requireCompleteExternalConfig();
    if (isExternalPostgresConfigured()) {
      registry.add("spring.datasource.url", () -> System.getenv("BANCO_PG_JDBC_URL"));
      registry.add("spring.datasource.username", () -> System.getenv("BANCO_PG_USER"));
      registry.add("spring.datasource.password", () -> System.getenv("BANCO_PG_PASSWORD"));
      registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
      registry.add("spring.flyway.url", () -> System.getenv("BANCO_PG_JDBC_URL"));
      registry.add("spring.flyway.user", () -> System.getenv("BANCO_PG_USER"));
      registry.add("spring.flyway.password", () -> System.getenv("BANCO_PG_PASSWORD"));
      registry.add("spring.flyway.enabled", () -> "true");
      registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
      return;
    }
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
