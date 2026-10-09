package com.bancoelcapital.financialops.transfer;

import java.util.UUID;

/** Determinable transfer result: operation plus whether this attempt confirmed it. */
public record TransferResult(UUID operationId, boolean created) {}
