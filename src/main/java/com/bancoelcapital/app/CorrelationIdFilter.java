package com.bancoelcapital.app;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Diagnostic correlation for HTTP requests (ADR-12 Acceptance Supplement).
 *
 * <p>The filter resolves the correlation identifier from the accepted inbound header (validated or
 * replaced) and generates a request identifier, publishes both in the diagnostic context (MDC) for
 * the duration of the request and clears them in a {@code finally} block, so a pooled thread cannot
 * leak them into a later, unrelated request. The cleanup is protected by an inner {@code finally},
 * so the context is cleared even when producing the record itself fails. Only the correlation
 * identifier is echoed to the client; the request identifier stays internal.
 *
 * <p>Exactly one diagnostic line is emitted per synchronous request that reaches this component,
 * with a fixed allow-list of fields and no request bodies, credentials, financial identifiers,
 * personal data, idempotency keys, actor headers, exception messages or stack traces. The route
 * template is read after the filter chain and reported as {@code unmatched} when the request never
 * reached a mapped handler. The recorded status is the value observed when the line is produced;
 * when an exception escapes the filter, the final status is decided by the container afterwards.
 *
 * <p>Only the initial {@code REQUEST} dispatch is recorded: error, asynchronous, forward and
 * include dispatches are skipped, so at most one line is produced per logical synchronous request.
 * Asynchronous request processing is outside this increment. Only exceptions are classified by
 * type: errors propagate unclassified. This class is not registered as a bean by itself; wiring is
 * a later phase.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  /** Inbound and outbound correlation header. */
  public static final String CORRELATION_HEADER = "X-Correlation-Id";

  /** Diagnostic context key for the correlation identifier. */
  public static final String CORRELATION_ID_KEY = "correlationId";

  /** Diagnostic context key for the request identifier. */
  public static final String REQUEST_ID_KEY = "requestId";

  static final String UNMATCHED_ROUTE = "unmatched";

  private static final String NO_EXCEPTION = "none";

  private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = CorrelationIds.resolve(request.getHeader(CORRELATION_HEADER));
    String requestId = CorrelationIds.generate();
    long startedAt = System.nanoTime();
    String failureType = NO_EXCEPTION;

    MDC.put(CORRELATION_ID_KEY, correlationId);
    MDC.put(REQUEST_ID_KEY, requestId);
    try {
      response.setHeader(CORRELATION_HEADER, correlationId);
      chain.doFilter(request, response);
    } catch (Exception failure) {
      failureType = failure.getClass().getSimpleName();
      throw failure;
    } finally {
      try {
        logDiagnostic(request, response, correlationId, requestId, failureType, startedAt);
      } finally {
        MDC.remove(CORRELATION_ID_KEY);
        MDC.remove(REQUEST_ID_KEY);
      }
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getDispatcherType() != DispatcherType.REQUEST;
  }

  private static void logDiagnostic(
      HttpServletRequest request,
      HttpServletResponse response,
      String correlationId,
      String requestId,
      String failureType,
      long startedAt) {
    log.info(
        "http.request correlationId={} requestId={} method={} route={} status={} durationMs={}"
            + " exceptionType={}",
        correlationId,
        requestId,
        request.getMethod(),
        routeTemplate(request),
        response.getStatus(),
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
        failureType);
  }

  private static String routeTemplate(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (pattern == null) {
      return UNMATCHED_ROUTE;
    }
    String template = pattern.toString();
    return template.isBlank() ? UNMATCHED_ROUTE : template;
  }
}
