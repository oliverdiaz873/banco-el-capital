package com.bancoelcapital.accounts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Transition matrix proof (ADR-19, Phase 1, domain only): every current-status plus requested
 * transition pair resolves deterministically to confirm, state-convergence no-op, or rejection. No
 * persistence, no service, no I/O here.
 */
class TransitionRulesTest {

  @Test
  void blockFromActiveConfirmsBlocked() {
    assertThat(TransitionRules.evaluate(AccountStatus.ACTIVE, AccountTransition.BLOCK))
        .isEqualTo(new TransitionRules.Decision.Confirm(AccountStatus.BLOCKED));
  }

  @Test
  void unblockFromBlockedConfirmsActive() {
    assertThat(TransitionRules.evaluate(AccountStatus.BLOCKED, AccountTransition.UNBLOCK))
        .isEqualTo(new TransitionRules.Decision.Confirm(AccountStatus.ACTIVE));
  }

  @ParameterizedTest
  @CsvSource({"ACTIVE, CLOSE", "BLOCKED, CLOSE"})
  void closeFromNonTerminalConfirmsClosed(AccountStatus current, AccountTransition requested) {
    assertThat(TransitionRules.evaluate(current, requested))
        .isEqualTo(new TransitionRules.Decision.Confirm(AccountStatus.CLOSED));
  }

  @ParameterizedTest
  @CsvSource({"BLOCKED, BLOCK", "ACTIVE, UNBLOCK"})
  void sameStateConvergesWithoutEffect(AccountStatus current, AccountTransition requested) {
    assertThat(TransitionRules.evaluate(current, requested))
        .isEqualTo(new TransitionRules.Decision.NoOp(current));
  }

  @ParameterizedTest
  @CsvSource({"CLOSED, BLOCK", "CLOSED, UNBLOCK", "CLOSED, CLOSE"})
  void terminalClosedRejectsEveryTransition(AccountStatus current, AccountTransition requested) {
    assertThat(TransitionRules.evaluate(current, requested))
        .isInstanceOf(TransitionRules.Decision.Reject.class);
  }
}
