package com.bancoelcapital.app;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation identifier contract for diagnostic observability (ADR-12 Acceptance Supplement).
 *
 * <p>The accepted bound is a NEW convention for this identifier: it mirrors the character set and
 * maximum length already applied to the client-provided {@code customerId} and does NOT establish a
 * general rule for every external identifier. A value is accepted only when it is made of up to
 * {@link #MAX_LENGTH} characters from {@code [A-Za-z0-9._-]}; a malformed or hostile incoming value
 * is replaced, never rejected: a bad correlation identifier MUST NOT fail, alter or block a
 * request.
 *
 * <p>Only validation and generation live here. HTTP propagation, the response echo and the
 * diagnostic-context (MDC) lifecycle are later phases and are deliberately not implemented. The
 * value is never an authorization or identity input.
 */
public final class CorrelationIds {

  /** Documented maximum accepted length. */
  public static final int MAX_LENGTH = 64;

  private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  private CorrelationIds() {}

  /** Returns the incoming value when it satisfies the bound, otherwise a newly generated one. */
  public static String resolve(String incoming) {
    return isValid(incoming) ? incoming : generate();
  }

  /** Whether the candidate satisfies the accepted bound. Null and blank values are not valid. */
  public static boolean isValid(String candidate) {
    return candidate != null && ACCEPTED.matcher(candidate).matches();
  }

  /** Generates a new identifier that satisfies the accepted bound. */
  public static String generate() {
    return UUID.randomUUID().toString();
  }
}
