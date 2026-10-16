package com.bancoelcapital.financialops.transfer.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Enforces {@link TransferDestinationXor}: direct intent carries destinationAccountId only,
 * beneficiary intent carries beneficiaryId only. Null requests are valid here; presence is enforced
 * by field-level constraints.
 */
public class TransferDestinationXorValidator
    implements ConstraintValidator<TransferDestinationXor, CreateTransferRequest> {

  @Override
  public boolean isValid(CreateTransferRequest request, ConstraintValidatorContext context) {
    if (request == null) {
      return true;
    }
    return (request.destinationAccountId() == null) != (request.beneficiaryId() == null);
  }
}
