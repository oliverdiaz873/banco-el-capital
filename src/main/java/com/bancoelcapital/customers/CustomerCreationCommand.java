package com.bancoelcapital.customers;

/** Intent to create a customer (Identity/Customers module). */
public record CustomerCreationCommand(String customerId, String displayName) {}
