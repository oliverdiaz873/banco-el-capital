package com.bancoelcapital.api;

import java.util.UUID;

public record AccountResponse(UUID accountId, boolean created) {}
