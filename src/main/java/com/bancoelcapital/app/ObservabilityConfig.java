package com.bancoelcapital.app;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Application wiring for observability (ADR-12 Acceptance Supplement, wiring phase). The diagnostic
 * correlation filter is registered explicitly through a {@link FilterRegistrationBean} instead of
 * component scanning, so registration happens exactly once, is visible in the servlet context, and
 * cannot be duplicated by an accidental stereotype annotation on the filter itself.
 *
 * <p>The filter is placed first in the servlet filter chain: the correlation and request
 * identifiers must exist before any downstream filter or component runs, and the echoed correlation
 * header must be set before anything can commit the response. Every request is covered, management
 * routes included; only the initial request dispatch is recorded, so the error page and
 * asynchronous dispatches do not produce a second diagnostic line.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

  /**
   * Registers the correlation filter for every request, first in the chain.
   *
   * @return the single registration of {@link CorrelationIdFilter}
   */
  @Bean
  FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
    FilterRegistrationBean<CorrelationIdFilter> registration =
        new FilterRegistrationBean<>(new CorrelationIdFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    return registration;
  }
}
