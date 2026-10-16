package com.bancoelcapital.financialops.transfer.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * XOR destination rule for transfer requests (ADR-21, approved D3): exactly one of
 * destinationAccountId or beneficiaryId must be present. Violations are input errors (HTTP 400)
 * that persist nothing.
 */
@Documented
@Constraint(validatedBy = TransferDestinationXorValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TransferDestinationXor {

  String message() default "exactly one of destinationAccountId or beneficiaryId must be present";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
