package com.bancoelcapital.api;

import java.util.UUID;

public record WithdrawalResponse(UUID operationId, boolean created) {}
