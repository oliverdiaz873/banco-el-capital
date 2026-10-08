package com.bancoelcapital.accounts.web;

import java.util.UUID;

public record AccountResponse(UUID accountId, boolean created) {}
