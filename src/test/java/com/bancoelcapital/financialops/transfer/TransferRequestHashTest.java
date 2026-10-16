package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * P0: path-specific canonical hashes. Direct vectors are independently computed (SHA-256 over the
 * documented canonical string) and must stay byte-for-byte compatible; beneficiary vectors follow
 * the approved E3 shape. No Spring context, no database, no sleeps.
 */
class TransferRequestHashTest {

  static final UUID SOURCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID DESTINATION = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void directHashRemainsByteCompatible() {
    var command = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    assertThat(TransferService.requestHash(command))
        .isEqualTo("fd1e7df834be1f5eb47701676419036d2f2789506ede1935428e981b43753175");
  }

  @Test
  void legacyFourArgCommandHashesAsDirect() {
    var legacy = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    var explicit = new TransferCommand(SOURCE, DESTINATION, null, 1000L, "DOP");
    assertThat(TransferService.requestHash(legacy))
        .isEqualTo(TransferService.requestHash(explicit));
  }

  @Test
  void beneficiaryCanonicalStringProducesExpectedDigest() {
    var command = new TransferCommand(SOURCE, null, DESTINATION, 1000L, "DOP");
    assertThat(TransferService.requestHash(command))
        .isEqualTo("39b2de55b2f122a264ab56e3e03e67cdb481a4a26d367b5bfcc528ab6dbcb86d");
  }

  @Test
  void beneficiaryDigestIs64HexChars() {
    var command = new TransferCommand(SOURCE, null, DESTINATION, 1000L, "DOP");
    assertThat(TransferService.requestHash(command)).matches("[0-9a-f]{64}");
  }

  @Test
  void beneficiaryHashNormalizesCurrencyCase() {
    var upper = new TransferCommand(SOURCE, null, DESTINATION, 1000L, "DOP");
    var lower = new TransferCommand(SOURCE, null, DESTINATION, 1000L, "dop");
    assertThat(TransferService.requestHash(lower)).isEqualTo(TransferService.requestHash(upper));
  }

  @Test
  void beneficiaryHashUsesBase10Amount() {
    var plain = new TransferCommand(SOURCE, null, DESTINATION, 1000L, "DOP");
    var other = new TransferCommand(SOURCE, null, DESTINATION, 1001L, "DOP");
    assertThat(TransferService.requestHash(plain)).isNotEqualTo(TransferService.requestHash(other));
  }

  @Test
  void directAndBeneficiaryPathsNeverShareIdentity() {
    var direct = new TransferCommand(SOURCE, DESTINATION, 1000L, "DOP");
    var beneficiary = new TransferCommand(SOURCE, null, DESTINATION, 1000L, "DOP");
    assertThat(TransferService.requestHash(beneficiary))
        .isNotEqualTo(TransferService.requestHash(direct));
  }
}
