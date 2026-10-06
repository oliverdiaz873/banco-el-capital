package com.bancoelcapital.financialops;

import java.util.UUID;

/** Determinable deposit result: operation plus whether this attempt confirmed it. */
public record DepositResult(UUID operationId, boolean created) {}
