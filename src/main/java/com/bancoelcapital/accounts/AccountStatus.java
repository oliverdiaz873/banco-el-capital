package com.bancoelcapital.accounts;

/** Account lifecycle states, MVP subset per ADR-03. PENDING is out of MVP. */
public enum AccountStatus {
  ACTIVE,
  BLOCKED,
  CLOSED
}
