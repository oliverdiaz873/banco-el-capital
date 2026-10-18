package com.bancoelcapital.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletContext;

/**
 * Registration and ordering of the diagnostic correlation filter (ADR-12 Acceptance Supplement,
 * wiring phase). It exercises the real servlet container over HTTP instead of a mock, so it proves
 * what unit tests cannot: the filter is registered exactly once, enabled, first in the chain and
 * applied to every request including management routes, and instrumentation is not duplicated when
 * the request is followed by the container error handling.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:correlation-registration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class ObservabilityConfigTest {

  @Autowired TestRestTemplate http;

  @Autowired ServletContext servletContext;

  @Autowired FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration;

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
  void registersTheCorrelationFilterOnceEnabledAndFirstInTheChain() {
    assertThat(correlationIdFilterRegistration.getFilter()).isInstanceOf(CorrelationIdFilter.class);
    assertThat(correlationIdFilterRegistration.isEnabled()).isTrue();
    assertThat(correlationIdFilterRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(correlationIdFilterRegistration.getUrlPatterns()).containsExactly("/*");

    long registeredInTheServletContext =
        servletContext.getFilterRegistrations().values().stream()
            .filter(
                registration ->
                    CorrelationIdFilter.class.getName().equals(registration.getClassName()))
            .count();

    assertThat(registeredInTheServletContext).isEqualTo(1);
  }

  @Test
  void correlatesARealManagementRequestAndRecordsOneLineForIt() {
    appender.list.clear();

    ResponseEntity<String> response = get("/actuator/health", "wire-management-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_HEADER))
        .isEqualTo("wire-management-1");
    assertThat(response.getHeaders().getFirst(CorrelationIdFilter.REQUEST_ID_KEY)).isNull();
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("correlationId=wire-management-1")
        .contains("method=GET")
        .contains("status=200")
        .contains("route=/actuator/health");
  }

  @Test
  void keepsTheCorrelationOnTheErrorPathWithoutRecordingTheRequestTwice() {
    appender.list.clear();

    ResponseEntity<String> response = get("/no-such-resource", "wire-error-path-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_HEADER))
        .isEqualTo("wire-error-path-1");
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("correlationId=wire-error-path-1")
        .contains("method=GET")
        .contains("status=404");
  }

  private ResponseEntity<String> get(String path, String correlationId) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.CORRELATION_HEADER, correlationId);
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
