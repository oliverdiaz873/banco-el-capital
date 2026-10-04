package com.bancoelcapital.identity;

/** Minimal authorization decision: who MAY create an account for a holder. */
public record AuthorizationDecision(boolean allowed, String reason) {
  public static AuthorizationDecision allow() {
    return new AuthorizationDecision(true, "authorized");
  }

  public static AuthorizationDecision deny(String reason) {
    return new AuthorizationDecision(false, reason);
  }
}
