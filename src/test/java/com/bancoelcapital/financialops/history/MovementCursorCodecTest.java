package com.bancoelcapital.financialops.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Cursor codec precision and fail-closed validation (ADR-22). The token is a transport convenience
 * only: no secret, no signature, no encryption and no PII.
 */
class MovementCursorCodecTest {

  private final MovementCursorCodec codec = new MovementCursorCodec();

  @Test
  void roundTripsWholeMicrosecondsExactlyIncludingTiesAndNegativeEpochs() {
    long[] samples = {
      0L,
      1L,
      999_999L,
      1_000_000L,
      1_700_000_000_123_456L,
      1_700_000_000_123_457L,
      -1L,
      -999_999L,
      -1_000_000L,
      -1_234_567L,
      Long.MAX_VALUE,
      -9_223_372_036_853_999_999L
    };
    for (long micros : samples) {
      Instant instant = instantOfMicros(micros);
      UUID id = UUID.randomUUID();

      String token = codec.encode(instant, id);
      MovementCursorCodec.Decoded decoded = codec.decode(token);

      assertThat(decoded.createdAt()).isEqualTo(instant);
      assertThat(decoded.id()).isEqualTo(id);
      assertThat(microsOf(decoded.createdAt())).isEqualTo(micros);
    }
  }

  @Test
  void truncatesNanosecondsWithoutRoundingUp() {
    Instant withNanoseconds = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L);

    MovementCursorCodec.Decoded decoded =
        codec.decode(codec.encode(withNanoseconds, UUID.randomUUID()));

    assertThat(microsOf(decoded.createdAt())).isEqualTo(1_700_000_000_123_456L);
    assertThat(decoded.createdAt().getNano()).isEqualTo(123_456_000);
  }

  @Test
  void canonicalTokenLengthStaysWithinTheDocumentedBounds() {
    String smallest = codec.encode(instantOfMicros(0L), UUID.randomUUID());
    String largest = codec.encode(instantOfMicros(-9_223_372_036_853_999_999L), UUID.randomUUID());

    assertThat(smallest).hasSize(51);
    assertThat(largest).hasSize(76);
  }

  @Test
  void acceptsAnIndependentlyBuiltCanonicalTokenWithoutAnySecret() {
    String payload = "5:00000000-0000-0000-0000-000000000001";
    String token = base64Url(payload);

    MovementCursorCodec.Decoded decoded = codec.decode(token);

    assertThat(microsOf(decoded.createdAt())).isEqualTo(5L);
    assertThat(decoded.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
  }

  @Test
  void rejectsEmptyPaddedOverLongAndNonBase64Tokens() {
    UUID id = UUID.randomUUID();
    String valid = codec.encode(Instant.ofEpochSecond(1), id);

    assertRejected("");
    assertRejected(valid + "=");
    assertRejected("a".repeat(77));
    assertRejected("****");
    assertRejected(valid + " ");
    assertRejected("ab cd");
  }

  @Test
  void rejectsStructurallyIncorrectAndNonCanonicalPayloads() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

    assertRejected(base64Url(id.toString()));
    assertRejected(base64Url("5:"));
    assertRejected(base64Url("5:" + id + ":extra"));
    assertRejected(base64Url(":5:" + id));
    assertRejected(base64Url("05:" + id));
    assertRejected(base64Url("-0:" + id));
    assertRejected(base64Url("+5:" + id));
    assertRejected(base64Url("5:AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"));
    assertRejected(base64Url("5:00000000-0000-0000-0000-00000000000"));
    assertRejected(base64Url("99999999999999999999:" + id));
  }

  @Test
  void doesNotLeakInternalDetailOnRejection() {
    assertThatThrownBy(() -> codec.decode("not-a-token"))
        .isInstanceOf(MovementHistoryException.class)
        .satisfies(
            e ->
                assertThat(((MovementHistoryException) e).getKind())
                    .isEqualTo(MovementHistoryException.Kind.INVALID))
        .hasMessage("malformed cursor");
  }

  private void assertRejected(String token) {
    assertThatThrownBy(() -> codec.decode(token))
        .isInstanceOf(MovementHistoryException.class)
        .satisfies(
            e ->
                assertThat(((MovementHistoryException) e).getKind())
                    .isEqualTo(MovementHistoryException.Kind.INVALID));
  }

  private static String base64Url(String payload) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  private static Instant instantOfMicros(long micros) {
    return Instant.ofEpochSecond(
        Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
  }

  private static long microsOf(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000L);
  }
}
