package com.bancoelcapital.api;

import java.util.UUID;

public record DepositResponse(UUID operationId, boolean created) {}
