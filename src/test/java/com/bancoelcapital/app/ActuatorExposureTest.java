package com.bancoelcapital.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Actuator HTTP exposure and data safety (ADR-12 Acceptance Supplement, data-safety phase). Real
 * HTTP against the servlet container, so the assertions observe runtime behaviour and not the YAML:
 * only {@code health} is reachable, it carries no {@code components} or {@code details}, and the
 * management routes keep the expected correlation behaviour, recording exactly one line per request
 * and never echoing the internal request identifier.
 *
 * <p>{@link ActuatorExposureUnderTestProfileTest} repeats these same checks under the {@code test}
 * profile, which declares no management configuration, proving that a profile does not widen the
 * exposure.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:actuator-exposure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class ActuatorExposureTest {

  /** Management endpoints that must stay unreachable over HTTP in this increment. */
  private static final List<String> NOT_EXPOSED_ENDPOINTS =
      List.of(
          "info",
          "metrics",
          "env",
          "beans",
          "loggers",
          "mappings",
          "configprops",
          "threaddump",
          "heapdump",
          "scheduledtasks",
          "auditevents",
          "caches",
          "conditions",
          "flyway");

  @Autowired TestRestTemplate http;

  private Logger filterLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    filterLogger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
    appender = new ListAppender<>();
    appender.start();
    filterLogger.addAppender(appender);
    filterLogger.setLevel(Level.INFO);
  }

  @AfterEach
  void detachAppender() {
    filterLogger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void exposesHealthWithoutComponentsOrDetails() {
    ResponseEntity<String> response = get("/actuator/health", "actuator-health-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    assertThat(response.getBody()).doesNotContain("components").doesNotContain("details");
  }

  @Test
  void doesNotExposeInfoOrMetrics() {
    assertNotExposed("/actuator/info", "actuator-info-1");
    assertNotExposed("/actuator/metrics", "actuator-metrics-1");
  }

  @Test
  void doesNotExposeAnyOtherManagementEndpoint() {
    for (String endpoint : NOT_EXPOSED_ENDPOINTS) {
      assertNotExposed("/actuator/" + endpoint, "actuator-" + endpoint + "-1");
    }
  }

  @Test
  void advertisesOnlyHealthInTheDiscoveryDocument() {
    ResponseEntity<String> response = get("/actuator", "actuator-discovery-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("health");
    for (String endpoint : NOT_EXPOSED_ENDPOINTS) {
      assertThat(response.getBody()).as(endpoint).doesNotContain("\"" + endpoint + "\"");
    }
  }

  @Test
  void keepsCorrelationOnManagementRequestsWithoutDuplicatingOrEchoingTheRequestId() {
    ResponseEntity<String> exposed = get("/actuator/health", "actuator-correlated-200");

    assertThat(exposed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(exposed.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_HEADER))
        .isEqualTo("actuator-correlated-200");
    assertThat(exposed.getHeaders().getFirst(CorrelationIdFilter.REQUEST_ID_KEY)).isNull();
    assertThat(appender.list).hasSize(1);

    ResponseEntity<String> unreachable = get("/actuator/metrics", "actuator-correlated-404");

    assertThat(unreachable.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(unreachable.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_HEADER))
        .isEqualTo("actuator-correlated-404");
    assertThat(unreachable.getHeaders().getFirst(CorrelationIdFilter.REQUEST_ID_KEY)).isNull();
    assertThat(appender.list).hasSize(1);
  }

  private void assertNotExposed(String path, String correlationId) {
    ResponseEntity<String> response = get(path, correlationId);

    assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_HEADER))
        .as(path)
        .isEqualTo(correlationId);
    assertThat(response.getHeaders().getFirst(CorrelationIdFilter.REQUEST_ID_KEY))
        .as(path)
        .isNull();
    assertThat(appender.list).as(path).hasSize(1);
  }

  private ResponseEntity<String> get(String path, String correlationId) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.CORRELATION_HEADER, correlationId);
    appender.list.clear();
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
