package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.accounts.internal.AccountStatus;
import com.bancoelcapital.accounts.internal.AccountTransition;
import com.bancoelcapital.accounts.internal.AccountTransitionService;
import com.bancoelcapital.accounts.internal.TransitionCommand;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositOperationException;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.financialops.transfer.TransferCommand;
import com.bancoelcapital.financialops.transfer.TransferOperationException;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.financialops.withdrawal.WithdrawalCommand;
import com.bancoelcapital.financialops.withdrawal.WithdrawalOperationException;
import com.bancoelcapital.financialops.withdrawal.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Operability coherence against real PostgreSQL: blocking excludes the account from all money paths
 * with balances intact, unblocking restores them, and closing an emptied account freezes it
 * terminally. Per-account stored balances still reconcile with confirmed movements.
 */
class TransitionPostgresReconciliationTest extends AbstractPostgresTest {

  @Autowired AccountTransitionService transitions;

  @Autowired DepositService deposits;

  @Autowired WithdrawalService withdrawals;

  @Autowired TransferService money;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor employee;
  AuthenticatedActor holderActor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String sourceHolder = "holder-pgtr-src-" + tag;
    String destinationHolder = "holder-pgtr-dst-" + tag;
    employee = new AuthenticatedActor("emp-" + tag, Set.of("BANK_EMPLOYEE"));
    holderActor = new AuthenticatedActor(sourceHolder, Set.of());
    var destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-" + tag, holderActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "d-" + tag, destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceHolder, "DOP", "BASIC"), "a-" + tag, holderActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "b-" + tag,
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 40_00L, "DOP"), "wr-" + tag, holderActor);
  }

  @Test
  void blockedAccountRejectsAllMoneyWithBalancesIntact() {
    transitions.transition(
        new TransitionCommand(sourceId, AccountTransition.BLOCK), "tb-" + tag, employee);

    assertThatThrownBy(
            () ->
                deposits.deposit(
                    new DepositCommand(sourceId, 1_00L, "DOP"), "wd-" + tag, holderActor))
        .isInstanceOf(DepositOperationException.class);
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    new WithdrawalCommand(sourceId, 1_00L, "DOP"), "ww-" + tag, holderActor))
        .isInstanceOf(WithdrawalOperationException.class);
    assertThatThrownBy(
            () ->
                money.transfer(
                    new TransferCommand(sourceId, destinationId, 1_00L, "DOP"),
                    "wt-" + tag,
                    holderActor))
        .isInstanceOf(TransferOperationException.class);

    assertThat(balanceOf(sourceId)).isEqualTo(40_00L);
    assertThat(balanceOf(destinationId)).isZero();
    assertThat(statusOf(sourceId)).isEqualTo(AccountStatus.BLOCKED.name());
  }

  @Test
  void unblockedAccountFlowsMoneyAgain() {
    transitions.transition(
        new TransitionCommand(sourceId, AccountTransition.BLOCK), "tb-" + tag, employee);
    transitions.transition(
        new TransitionCommand(sourceId, AccountTransition.UNBLOCK), "tu-" + tag, employee);

    deposits.deposit(new DepositCommand(sourceId, 10_00L, "DOP"), "wd2-" + tag, holderActor);

    assertThat(statusOf(sourceId)).isEqualTo(AccountStatus.ACTIVE.name());
    assertThat(balanceOf(sourceId)).isEqualTo(50_00L);
    assertThat(creditsMinusDebits(sourceId)).isEqualTo(50_00L);
  }

  @Test
  void closedEmptiedAccountStaysFrozen() {
    withdrawals.withdraw(
        new WithdrawalCommand(sourceId, 40_00L, "DOP"), "ww-full-" + tag, holderActor);
    transitions.transition(
        new TransitionCommand(sourceId, AccountTransition.CLOSE), "tc-" + tag, employee);

    assertThatThrownBy(
            () ->
                deposits.deposit(
                    new DepositCommand(sourceId, 1_00L, "DOP"), "wd3-" + tag, holderActor))
        .isInstanceOf(DepositOperationException.class);

    assertThat(statusOf(sourceId)).isEqualTo(AccountStatus.CLOSED.name());
    assertThat(balanceOf(sourceId)).isZero();
    assertThat(creditsMinusDebits(sourceId)).isZero();
  }

  private Long creditsMinusDebits(UUID accountId) {
    Long credits =
        jdbc.queryForObject(
            "select coalesce(sum(amount_minor_units), 0) from movements"
                + " where account_id = ? and direction = 'CREDIT'",
            Long.class,
            accountId);
    Long debits =
        jdbc.queryForObject(
            "select coalesce(sum(amount_minor_units), 0) from movements"
                + " where account_id = ? and direction = 'DEBIT'",
            Long.class,
            accountId);
    return credits - debits;
  }

  private String statusOf(UUID accountId) {
    return jdbc.queryForObject("select status from accounts where id = ?", String.class, accountId);
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
