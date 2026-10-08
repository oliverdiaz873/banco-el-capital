package com.bancoelcapital.financialops.core;

/** Financial operation lifecycle states (ADR-04 subset for deposits). */
public enum FinancialOperationStatus {
  PENDING,
  CONFIRMED,
  REJECTED,
  FAILED
}
