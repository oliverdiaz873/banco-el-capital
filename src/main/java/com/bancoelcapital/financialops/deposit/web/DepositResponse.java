package com.bancoelcapital.financialops.deposit.web;

import java.util.UUID;

public record DepositResponse(UUID operationId, boolean created) {}
