package com.bancoelcapital.accounts.web;

import java.util.UUID;

public record TransitionResponse(UUID accountId, String status, boolean changed) {}
