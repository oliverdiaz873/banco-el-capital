package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Scope B: hash-and-fields intent comparison. Matrix over match, per-field divergence, currency
 * canonicalization, and the hash-only legacy rule for historical (null-path) rows. No Spring
 * context, no database.
 */
class TransferIntentMatchTest {

  static final UUID SOURCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID DESTINATION = UUID.fromString("22222222-2222-2222-2222-222222222222");
  static final UUID BENEFICIARY = UUID.fromString("33333333-3333-3333-3333-333333333333");

  static TransferOperationIdempotency storedDirect(TransferCommand command) {
    return new TransferOperationIdempotency(
        "k",
        UUID.randomUUID(),
        TransferService.requestHash(command),
        TransferOperationOutcome.REJECTED,
        command.sourceAccountId(),
        null,
        "direct",
        command.amountMinorUnits(),
        command.currency());
  }

  static TransferOperationIdempotency storedBeneficiary(TransferCommand command) {
    return new TransferOperationIdempotency(
        "k",
        UUID.randomUUID(),
        TransferService.requestHash(command),
        TransferOperationOutcome.REJECTED,
        command.sourceAccountId(),
        command.beneficiaryId(),
        "beneficiary-transfer",
        command.amountMinorUnits(),
        command.currency());
  }

  @Test
  void directMatchIsTrue() {
    var command = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    assertThat(TransferService.matchesIntent(storedDirect(command), command, hashOf(command)))
        .isTrue();
  }

  @Test
  void beneficiaryMatchIsTrue() {
    var command = new TransferCommand(SOURCE, null, BENEFICIARY, 1000L, "DOP");
    assertThat(TransferService.matchesIntent(storedBeneficiary(command), command, hashOf(command)))
        .isTrue();
  }

  @Test
  void hashMismatchIsFalse() {
    var command = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    assertThat(TransferService.matchesIntent(storedDirect(command), command, "0".repeat(64)))
        .isFalse();
  }

  @Test
  void sourceDifferenceIsFalse() {
    var command = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    var other = new TransferCommand(UUID.randomUUID(), DESTINATION, 1000L, "DOP");
    assertThat(TransferService.matchesIntent(storedDirect(command), other, hashOf(other)))
        .isFalse();
  }

  @Test
  void amountDifferenceIsFalse() {
    var command = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    var other = new TransferCommand(SOURCE, DESTINATION, 2000L, "DOP");
    assertThat(TransferService.matchesIntent(storedDirect(command), other, hashOf(other)))
        .isFalse();
  }

  @Test
  void beneficiaryDifferenceIsFalse() {
    var command = new TransferCommand(SOURCE, null, BENEFICIARY, 1000L, "DOP");
    var other = new TransferCommand(SOURCE, null, UUID.randomUUID(), 1000L, "DOP");
    assertThat(TransferService.matchesIntent(storedBeneficiary(command), other, hashOf(other)))
        .isFalse();
  }

  @Test
  void pathDiscriminatorDifferenceIsFalse() {
    var direct = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    var beneficiary = new TransferCommand(SOURCE, null, DESTINATION, 1000L, "DOP");
    assertThat(
            TransferService.matchesIntent(storedDirect(direct), beneficiary, hashOf(beneficiary)))
        .isFalse();
    assertThat(
            TransferService.matchesIntent(storedBeneficiary(beneficiary), direct, hashOf(direct)))
        .isFalse();
  }

  @Test
  void currencyCaseVariantStillMatches() {
    var upper = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    var lower = new TransferCommand(SOURCE, DESTINATION, 1000L, "dop");
    assertThat(TransferService.matchesIntent(storedDirect(upper), lower, hashOf(lower))).isTrue();
  }

  @Test
  void historicalRowKeepsHashOnlyLegacy() {
    var command = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    var historical =
        new TransferOperationIdempotency(
            "k",
            UUID.randomUUID(),
            hashOf(command),
            TransferOperationOutcome.REJECTED,
            null,
            null,
            null,
            null,
            null);
    assertThat(TransferService.matchesIntent(historical, command, hashOf(command))).isTrue();
    assertThat(TransferService.matchesIntent(historical, command, "0".repeat(64))).isFalse();
  }

  @Test
  void getOverloadComparesHashOnly() {
    var command = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    var stored = storedDirect(command);
    assertThat(TransferService.matchesIntent(stored, hashOf(command))).isTrue();
    assertThat(TransferService.matchesIntent(stored, "0".repeat(64))).isFalse();
  }

  static String hashOf(TransferCommand command) {
    return TransferService.requestHash(command);
  }
}
