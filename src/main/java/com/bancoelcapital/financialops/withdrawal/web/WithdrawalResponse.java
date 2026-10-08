package com.bancoelcapital.financialops.withdrawal.web;

import java.util.UUID;

public record WithdrawalResponse(UUID operationId, boolean created) {}
