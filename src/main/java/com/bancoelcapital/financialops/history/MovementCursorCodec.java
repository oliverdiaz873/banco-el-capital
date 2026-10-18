package com.bancoelcapital.financialops.history;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Opaque keyset cursor codec (ADR-22). A token is the unpadded Base64URL of the canonical payload
 * {@code <micros>:<lowercase-uuid>}, where {@code <micros>} is a canonical signed 64-bit decimal
 * (no leading zeros, no {@code +} sign) and the id is the canonical 8-4-4-4-12 lowercase UUID.
 *
 * <p>The encoding is a transport convenience only: NOT a signature, NOT encryption and NOT proof of
 * authorization. Validation is strict and fail-closed; every rejection becomes a safe 400, never an
 * exception or internal detail. The codec round-trips whole microseconds exactly (the nanosecond
 * remainder is truncated, never rounded up), matching PostgreSQL {@code TIMESTAMPTZ}.
 */
@Component
public class MovementCursorCodec {

  private static final long MICROS_PER_SECOND = 1_000_000L;

  /** Longest canonical unpadded token: a 57-byte payload encodes to 76 Base64URL characters. */
  private static final int MAX_TOKEN_LENGTH = 76;

  /** Canonical 8-4-4-4-12 lowercase hexadecimal UUID. */
  private static final Pattern UUID_SHAPE =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  public String encode(Instant createdAt, UUID id) {
    String payload = micros(createdAt) + ":" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  public Decoded decode(String token) {
    if (token == null
        || token.isEmpty()
        || token.length() > MAX_TOKEN_LENGTH
        || token.indexOf('=') >= 0) {
      throw invalid();
    }
    byte[] payload;
    try {
      payload = Base64.getUrlDecoder().decode(token);
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
    // Canonical form: re-encoding the decoded bytes must reproduce the input exactly. This rejects
    // padded, non-alphabet and non-canonical spellings in one place.
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(payload).equals(token)) {
      throw invalid();
    }
    String decoded = new String(payload, StandardCharsets.UTF_8);
    int separator = decoded.indexOf(':');
    if (separator <= 0 || decoded.indexOf(':', separator + 1) >= 0) {
      throw invalid();
    }
    long micros = parseMicros(decoded.substring(0, separator));
    UUID id = parseId(decoded.substring(separator + 1));
    return new Decoded(toInstant(micros), id);
  }

  private static long micros(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), MICROS_PER_SECOND), instant.getNano() / 1000L);
  }

  private static Instant toInstant(long micros) {
    return Instant.ofEpochSecond(
        Math.floorDiv(micros, MICROS_PER_SECOND), Math.floorMod(micros, MICROS_PER_SECOND) * 1000L);
  }

  private static long parseMicros(String text) {
    long value;
    try {
      value = Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw invalid();
    }
    if (!text.equals(Long.toString(value))) {
      throw invalid();
    }
    return value;
  }

  private static UUID parseId(String text) {
    if (!UUID_SHAPE.matcher(text).matches()) {
      throw invalid();
    }
    return UUID.fromString(text);
  }

  private static MovementHistoryException invalid() {
    return new MovementHistoryException(MovementHistoryException.Kind.INVALID, "malformed cursor");
  }

  /** Decoded keyset position: the last item's exact {@code (createdAt, id)} pair. */
  public record Decoded(Instant createdAt, UUID id) {}
}
