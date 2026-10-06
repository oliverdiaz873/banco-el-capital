package com.bancoelcapital.financialops;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Diagnostic reconciliation (ADR-15): stored observable balance must equal the sum of CONFIRMED
 * movements for the account. Aggregation is the integrity check here, never the read path. H2 only;
 * PostgreSQL validation remains a follow-up and this test does not replace it.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:deposit-reconciliation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class DepositReconciliationTest {

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID accountId;
  AuthenticatedActor actor = new AuthenticatedActor("holder-recon", Set.of());

  @BeforeEach
  void setup() {
    customers.create(new CustomerCreationCommand("holder-recon", null), "c-recon", actor);
    var created =
        accounts.create(
            new AccountCreationCommand("holder-recon", "DOP", "BASIC"), "a-recon", actor);
    accountId = created.accountId();
  }

  @Test
  void storedBalanceEqualsSumOfConfirmedMovements() {
    deposits.deposit(new DepositCommand(accountId, 10_00L, "DOP"), "r-1", actor);
    deposits.deposit(new DepositCommand(accountId, 25_00L, "DOP"), "r-2", actor);
    deposits.deposit(new DepositCommand(accountId, 5_00L, "DOP"), "r-3", actor);

    Long balance =
        jdbc.queryForObject(
            "select balance_minor_units from accounts where id = ?", Long.class, accountId);
    Long movementsSum =
        jdbc.queryForObject(
            "select coalesce(sum(amount_minor_units), 0) from movements"
                + " where account_id = ? and direction = 'CREDIT'",
            Long.class,
            accountId);
    Long operationCount =
        jdbc.queryForObject(
            "select count(*) from financial_operations"
                + " where account_id = ? and status = 'CONFIRMED'",
            Long.class,
            accountId);

    assertThat(balance).isEqualTo(40_00L);
    assertThat(movementsSum).isEqualTo(40_00L);
    assertThat(balance).isEqualTo(movementsSum);
    assertThat(operationCount).isEqualTo(3);
  }

  @Test
  void rejectedDepositsLeaveBalanceAndMovementsUntouched() {
    try {
      deposits.deposit(new DepositCommand(accountId, 0L, "DOP"), "r-rej", actor);
    } catch (DepositOperationException rejected) {
      assertThat(rejected.getKind()).isEqualTo(DepositOperationException.Kind.REJECTED);
    }

    Long balance =
        jdbc.queryForObject(
            "select balance_minor_units from accounts where id = ?", Long.class, accountId);
    Long movements =
        jdbc.queryForObject(
            "select count(*) from movements where account_id = ?", Long.class, accountId);

    assertThat(balance).isZero();
    assertThat(movements).isZero();
  }
}
