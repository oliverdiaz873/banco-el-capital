package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bancoelcapital.accounts.AccountCreationCommand;
import com.bancoelcapital.accounts.AccountCreationService;
import com.bancoelcapital.customers.CustomerCreationCommand;
import com.bancoelcapital.customers.CustomerCreationService;
import com.bancoelcapital.financialops.DepositCommand;
import com.bancoelcapital.financialops.DepositService;
import com.bancoelcapital.financialops.WithdrawalCommand;
import com.bancoelcapital.financialops.WithdrawalOperationException;
import com.bancoelcapital.financialops.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Reconciliation against real PostgreSQL: stored balance equals confirmed credits minus confirmed
 * debits over a mixed deposit/withdrawal history; rejected withdrawals leave no movement behind.
 */
class WithdrawalPostgresReconciliationTest extends AbstractPostgresTest {

  @Autowired WithdrawalService withdrawals;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor actor;
  String tag;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-pgrc-" + tag;
    actor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, actor);
    var created =
        accounts.create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, actor);
    accountId = created.accountId();
  }

  @Test
  void storedBalanceEqualsCreditsMinusDebits() {
    deposits.deposit(new DepositCommand(accountId, 40_00L, "DOP"), "wr-" + tag, actor);
    withdrawals.withdraw(new WithdrawalCommand(accountId, 10_00L, "DOP"), "ww1-" + tag, actor);
    withdrawals.withdraw(new WithdrawalCommand(accountId, 5_00L, "DOP"), "ww2-" + tag, actor);

    assertThat(balance()).isEqualTo(25_00L);
    assertThat(creditsMinusDebits()).isEqualTo(25_00L);
  }

  @Test
  void rejectedWithdrawalIsExcludedFromMovements() {
    deposits.deposit(new DepositCommand(accountId, 10_00L, "DOP"), "wr2-" + tag, actor);
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    new WithdrawalCommand(accountId, 10_01L, "DOP"), "ww-rej-" + tag, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.REJECTED);

    assertThat(balance()).isEqualTo(10_00L);
    assertThat(creditsMinusDebits()).isEqualTo(10_00L);
    assertThat(debitCount()).isZero();
  }

  private Long creditsMinusDebits() {
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

  private Long debitCount() {
    return jdbc.queryForObject(
        "select count(*) from movements where account_id = ? and direction = 'DEBIT'",
        Long.class,
        accountId);
  }

  private Long balance() {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
