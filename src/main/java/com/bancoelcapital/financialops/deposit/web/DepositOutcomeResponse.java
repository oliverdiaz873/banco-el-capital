package com.bancoelcapital.financialops.deposit.web;

import java.util.UUID;

public record DepositOutcomeResponse(UUID operationId, String outcome) {}
