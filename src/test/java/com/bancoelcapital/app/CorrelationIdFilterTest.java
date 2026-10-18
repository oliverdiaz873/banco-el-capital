package com.bancoelcapital.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;

/**
 * Diagnostic correlation filter (ADR-12 Acceptance Supplement): one record per synchronous request,
 * only the correlation identifier echoed, the diagnostic context cleaned in a finally block, and an
 * allow-list of fields with no sensitive data.
 */
class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

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
    MDC.clear();
  }

  @Test
  void logsOneRecordEchoesOnlyTheCorrelationIdAndReportsTheRouteTemplate() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/42");
    request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, "client-supplied-1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    invoke(request, response, mapped("/api/v1/accounts/{id}", 200));

    assertThat(appender.list).hasSize(1);
    String record = appender.list.get(0).getFormattedMessage();
    assertThat(record).contains("correlationId=client-supplied-1");
    assertThat(record).contains("method=GET");
    assertThat(record).contains("route=/api/v1/accounts/{id}");
    assertThat(record).contains("status=200");
    assertThat(record).contains("exceptionType=none");
    assertThat(record).matches("(?s).*requestId=[0-9a-f-]{36}\\s.*");
    assertThat(record).doesNotContain("/api/v1/accounts/42");
    assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_HEADER))
        .isEqualTo("client-supplied-1");
    assertThat(response.getHeader(CorrelationIdFilter.REQUEST_ID_KEY)).isNull();
  }

  @Test
  void replacesAndNeverEchoesAnInvalidIncomingCorrelationId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
    request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, "bad id\r\nINJECTED-SENTINEL");
    MockHttpServletResponse response = new MockHttpServletResponse();

    invoke(request, response, mapped("/api/v1/transfers", 201));

    String echoed = response.getHeader(CorrelationIdFilter.CORRELATION_HEADER);
    assertThat(echoed).isNotNull();
    assertThat(echoed).doesNotContain("bad id").doesNotContain("INJECTED-SENTINEL");
    assertThat(CorrelationIds.isValid(echoed)).isTrue();
    assertThat(appender.list).hasSize(1);
    String record = appender.list.get(0).getFormattedMessage();
    assertThat(record).doesNotContain("bad id").doesNotContain("INJECTED-SENTINEL");
    assertThat(record).contains("correlationId=" + echoed);
  }

  @Test
  void populatesTheContextDuringTheRequestAndClearsItForTheNextRequestOnTheSameThread()
      throws Exception {
    MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/accounts");
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    StringBuilder observed = new StringBuilder();

    invoke(
        first,
        firstResponse,
        (req, res) -> {
          observed
              .append(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY))
              .append(':')
              .append(MDC.get(CorrelationIdFilter.REQUEST_ID_KEY));
        });

    assertThat(observed.toString()).matches("[A-Za-z0-9._-]{1,64}:[0-9a-f-]{36}");
    assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    assertThat(MDC.get(CorrelationIdFilter.REQUEST_ID_KEY)).isNull();

    MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/accounts");
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();

    invoke(second, secondResponse, mapped("/api/v1/accounts", 200));

    assertThat(appender.list).hasSize(2);
    assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    assertThat(MDC.get(CorrelationIdFilter.REQUEST_ID_KEY)).isNull();
    assertThat(secondResponse.getHeader(CorrelationIdFilter.CORRELATION_HEADER))
        .isNotEqualTo(firstResponse.getHeader(CorrelationIdFilter.CORRELATION_HEADER));
  }

  @Test
  void reportsUnmatchedWhenTheRequestNeverReachedAMappedHandler() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/not-mapped");
    MockHttpServletResponse response = new MockHttpServletResponse();

    invoke(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(404));

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage()).contains("route=unmatched");
  }

  @Test
  void neverRecordsBodiesCredentialsActorIdempotencyOrRawUriData() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
    request.setContent(
        "{\"accountId\":\"11111111-1111-1111-1111-111111111111\"}"
            .getBytes(StandardCharsets.UTF_8));
    request.setQueryString("secret=TOPSECRET-QUERY-SENTINEL");
    request.addHeader("X-Actor-Id", "ACTOR-SENTINEL");
    request.addHeader("Authorization", "Bearer TOKEN-SENTINEL");
    request.addHeader("Idempotency-Key", "IDEMPOTENCY-SENTINEL");
    request.addHeader("Idempotency-Key-Hash", "HASH-SENTINEL");
    request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, "valid-correlation-1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    invoke(request, response, mapped("/api/v1/transfers", 201));

    assertThat(appender.list).hasSize(1);
    String record = appender.list.get(0).getFormattedMessage();
    assertThat(record).contains("route=/api/v1/transfers");
    assertThat(record)
        .doesNotContain("ACTOR-SENTINEL")
        .doesNotContain("TOKEN-SENTINEL")
        .doesNotContain("IDEMPOTENCY-SENTINEL")
        .doesNotContain("HASH-SENTINEL")
        .doesNotContain("TOPSECRET-QUERY-SENTINEL")
        .doesNotContain("11111111-1111-1111-1111-111111111111");
  }

  @Test
  void recordsTheExceptionTypeWithoutMessageOrStackTraceAndStillPropagates() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transfers");
    request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, "valid-correlation-1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    response,
                    (req, res) -> {
                      throw new IllegalStateException("SENSITIVE-EXCEPTION-MESSAGE");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SENSITIVE-EXCEPTION-MESSAGE");

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getFormattedMessage()).contains("exceptionType=IllegalStateException");
    assertThat(event.getFormattedMessage()).doesNotContain("SENSITIVE-EXCEPTION-MESSAGE");
    assertThat(event.getFormattedMessage()).doesNotContain("\tat ");
    assertThat(event.getThrowableProxy()).isNull();
    assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    assertThat(MDC.get(CorrelationIdFilter.REQUEST_ID_KEY)).isNull();
  }

  @Test
  void recordsOnlyTheInitialRequestDispatchAndSkipsEveryOtherDispatcherType() throws Exception {
    DispatcherType[] otherDispatches = {
      DispatcherType.ERROR, DispatcherType.ASYNC, DispatcherType.FORWARD, DispatcherType.INCLUDE
    };

    for (DispatcherType type : otherDispatches) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
      request.setDispatcherType(type);
      MockHttpServletResponse response = new MockHttpServletResponse();

      invoke(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(500));
    }

    assertThat(appender.list).isEmpty();
    assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
    assertThat(MDC.get(CorrelationIdFilter.REQUEST_ID_KEY)).isNull();
  }

  @Test
  void clearsTheContextEvenWhenTheDiagnosticRecordItselfFails() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FailingAppender failing = new FailingAppender();
    failing.setContext(filterLogger.getLoggerContext());
    failing.start();
    filterLogger.addAppender(failing);

    try {
      assertThatThrownBy(() -> invoke(request, response, mapped("/api/v1/accounts", 200)))
          .isInstanceOf(AssertionError.class)
          .hasMessage("diagnostic record failure");

      assertThat(appender.list).hasSize(1);
      assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
      assertThat(MDC.get(CorrelationIdFilter.REQUEST_ID_KEY)).isNull();
    } finally {
      filterLogger.detachAppender(failing);
      failing.stop();
    }
  }

  private static final class FailingAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
      throw new AssertionError("diagnostic record failure");
    }
  }

  private void invoke(
      MockHttpServletRequest request, MockHttpServletResponse response, FilterChain chain)
      throws Exception {
    filter.doFilter(request, response, chain);
  }

  private static FilterChain mapped(String template, int status) {
    return (req, res) -> {
      req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
      ((MockHttpServletResponse) res).setStatus(status);
    };
  }
}
