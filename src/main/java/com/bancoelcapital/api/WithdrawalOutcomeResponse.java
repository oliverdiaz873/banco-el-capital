package com.bancoelcapital.api;

import java.util.UUID;

public record WithdrawalOutcomeResponse(UUID operationId, String outcome) {}
