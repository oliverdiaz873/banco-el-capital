package com.bancoelcapital.customers;

/** Determinable customer-creation result: id plus whether this attempt created it. */
public record CustomerResult(String customerId, boolean created) {}
