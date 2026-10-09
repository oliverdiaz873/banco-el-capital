package com.bancoelcapital.financialops.transfer.web;

import java.util.UUID;

public record TransferResponse(UUID operationId, boolean created) {}
