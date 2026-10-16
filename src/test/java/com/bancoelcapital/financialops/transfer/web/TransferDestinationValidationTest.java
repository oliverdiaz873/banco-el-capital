package com.bancoelcapital.financialops.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * P0: XOR destination contract at the Bean Validation level. Direct requests carry
 * destinationAccountId only, beneficiary requests carry beneficiaryId only; both-present and
 * both-absent are rejected. HTTP mapping is covered by {@code TransferXorWebMappingTest}.
 */
class TransferDestinationValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  UUID sourceId = UUID.randomUUID();
  UUID destinationId = UUID.randomUUID();
  UUID beneficiaryId = UUID.randomUUID();

  @Test
  void directRequestWithOnlyDestinationIsAccepted() {
    var request = new CreateTransferRequest(sourceId, destinationId, null, 1000L, "DOP");
    assertThat(validator.validate(request)).isEmpty();
  }

  @Test
  void beneficiaryRequestWithOnlyBeneficiaryIsAccepted() {
    var request = new CreateTransferRequest(sourceId, null, beneficiaryId, 1000L, "DOP");
    assertThat(validator.validate(request)).isEmpty();
  }

  @Test
  void bothDestinationFieldsPresentIsRejected() {
    var request = new CreateTransferRequest(sourceId, destinationId, beneficiaryId, 1000L, "DOP");
    assertThat(validator.validate(request)).hasSize(1);
  }

  @Test
  void bothDestinationFieldsAbsentIsRejected() {
    var request = new CreateTransferRequest(sourceId, null, null, 1000L, "DOP");
    assertThat(validator.validate(request)).hasSize(1);
  }
}
