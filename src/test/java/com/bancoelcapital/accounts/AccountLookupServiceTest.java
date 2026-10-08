package com.bancoelcapital.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bancoelcapital.accounts.api.AccountLookupService;
import com.bancoelcapital.accounts.internal.Account;
import com.bancoelcapital.accounts.internal.AccountRepository;

@ExtendWith(MockitoExtension.class)
class AccountLookupServiceTest {

  @Mock AccountRepository accounts;

  AccountLookupService service;

  UUID accountId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    service = new AccountLookupService(accounts);
  }

  @Test
  void resolvesHolderForExistingAccount() {
    when(accounts.findById(accountId))
        .thenReturn(Optional.of(new Account(accountId, "holder-1", "DOP", "BASIC")));

    assertThat(service.findHolderCustomerId(accountId)).contains("holder-1");
  }

  @Test
  void emptyForUnknownOrNullAccount() {
    when(accounts.findById(accountId)).thenReturn(Optional.empty());

    assertThat(service.findHolderCustomerId(accountId)).isEmpty();
    assertThat(service.findHolderCustomerId(null)).isEmpty();
  }
}
