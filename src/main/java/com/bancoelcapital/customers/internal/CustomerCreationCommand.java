package com.bancoelcapital.customers.internal;

/** Intent to create a customer (Identity/Customers module). */
public record CustomerCreationCommand(String customerId, String displayName) {}
