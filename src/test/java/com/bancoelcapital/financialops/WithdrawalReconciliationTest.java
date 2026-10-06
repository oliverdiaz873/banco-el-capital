package com.bancoelcapital.financialops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bancoelcapital.accounts.AccountCreationCommand;
import com.bancoelcapital.accounts.AccountCreationService;
import com.bancoelcapital.customers.CustomerCreationCommand;
import com.bancoelcapital.customers.CustomerCreationService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Diagnostic reconciliation (ADR-15, ADR-16): stored observable balance must equal confirmed
 * credits minus confirmed debits for the account. Aggregation is the integrity check here, never
 * the read path. H2 only; PostgreSQL validation remains a follow-up.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:withdrawal-reconciliation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class WithdrawalReconciliationTest {

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
    // Fresh holder and account per test: the Spring context (and H2 mem db) is shared across
    // test methods in the class, so fixed ids would accumulate balance from previous tests.
    tag = UUID.randomUUID().toString().substring(0, 8);
    String holder = "holder-wrecon-" + tag;
    actor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-wrecon-" + tag, actor);
    var created =
        accounts.create(
            new AccountCreationCommand(holder, "DOP", "BASIC"), "a-wrecon-" + tag, actor);
    accountId = created.accountId();
  }

  @Test
  void storedBalanceEqualsCreditsMinusDebits() {
    deposits.deposit(new DepositCommand(accountId, 40_00L, "DOP"), "wr-" + tag, actor);
    withdrawals.withdraw(new WithdrawalCommand(accountId, 10_00L, "DOP"), "ww1-" + tag, actor);
    withdrawals.withdraw(new WithdrawalCommand(accountId, 5_00L, "DOP"), "ww2-" + tag, actor);

    assertThat(balance()).isEqualTo(25_00L);
    assertThat(creditsMinusDebits()).isEqualTo(25_00L);
    assertThat(balance()).isEqualTo(creditsMinusDebits());
  }

  @Test
  void rejectedWithdrawalLeavesBalanceAndMovementsUntouched() {
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
    assertThat(debitCount()).isZero();
    assertThat(creditsMinusDebits()).isEqualTo(10_00L);
  }

  private Long balance() {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
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
}
