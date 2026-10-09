package com.bancoelcapital.financialops.transfer.web;

import java.util.UUID;

public record TransferOutcomeResponse(UUID operationId, String outcome) {}
