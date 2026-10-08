package com.bancoelcapital.financialops.deposit;

import java.util.UUID;

/** Determinable deposit result: operation plus whether this attempt confirmed it. */
public record DepositResult(UUID operationId, boolean created) {}
