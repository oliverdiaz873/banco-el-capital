package com.bancoelcapital.financialops.withdrawal.web;

import java.util.UUID;

public record WithdrawalOutcomeResponse(UUID operationId, String outcome) {}
