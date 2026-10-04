package com.bancoelcapital.identity;

import org.springframework.stereotype.Service;

@Service
public class StubIdentityGateway implements IdentityGateway {

  @Override
  public boolean customerExists(String customerId) {
    return customerId != null && !customerId.isBlank();
  }
}
