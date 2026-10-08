package com.bancoelcapital.financialops.withdrawal;

import java.util.UUID;

/** Outcome of a withdrawal attempt: the operation identity and whether it was newly created. */
public record WithdrawalResult(UUID operationId, boolean created) {}
