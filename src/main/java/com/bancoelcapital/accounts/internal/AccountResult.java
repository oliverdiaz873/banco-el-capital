package com.bancoelcapital.accounts.internal;

import java.util.UUID;

/** Determinable creation result: account plus whether this attempt created it. */
public record AccountResult(UUID accountId, boolean created) {}
