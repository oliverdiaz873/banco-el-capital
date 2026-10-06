package com.bancoelcapital.api;

import java.util.UUID;

public record DepositOutcomeResponse(UUID operationId, String outcome) {}
