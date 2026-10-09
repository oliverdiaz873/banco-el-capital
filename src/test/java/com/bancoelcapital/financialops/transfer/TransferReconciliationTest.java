package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.financialops.withdrawal.WithdrawalCommand;
import com.bancoelcapital.financialops.withdrawal.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Diagnostic reconciliation (ADR-15, ADR-18 Option A): per account, the stored observable balance
 * must equal confirmed credits minus confirmed debits, including both transfer legs. Aggregation is
 * the integrity check here, never the read path. H2 only; PostgreSQL validation remains a
 * follow-up.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-reconciliation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferReconciliationTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired WithdrawalService withdrawals;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired JdbcTemplate jdbc;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;
  String tag;

  @BeforeEach
  void setup() {
    // Fresh holders and accounts per test: the Spring context (and H2 mem db) is shared across
    // test methods in the class, so fixed ids would accumulate balance from previous tests.
    tag = UUID.randomUUID().toString().substring(0, 8);
    String sourceHolder = "holder-trecon-src-" + tag;
    String destinationHolder = "holder-trecon-dst-" + tag;
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    var destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(
        new CustomerCreationCommand(sourceHolder, null), "c-trecon-s-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null),
        "c-trecon-d-" + tag,
        destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceHolder, "DOP", "BASIC"),
                "a-trecon-s-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "a-trecon-d-" + tag,
                destinationActor)
            .accountId();
  }

  @Test
  void storedBalancesEqualCreditsMinusDebitsOnBothLegs() {
    deposits.deposit(new DepositCommand(sourceId, 40_00L, "DOP"), "tr-" + tag, sourceActor);
    transfers.transfer(
        new TransferCommand(sourceId, destinationId, 10_00L, "DOP"), "tt-" + tag, sourceActor);
    withdrawals.withdraw(new WithdrawalCommand(sourceId, 5_00L, "DOP"), "tw-" + tag, sourceActor);

    assertThat(balanceOf(sourceId)).isEqualTo(25_00L);
    assertThat(creditsMinusDebits(sourceId)).isEqualTo(25_00L);
    assertThat(balanceOf(sourceId)).isEqualTo(creditsMinusDebits(sourceId));
    assertThat(balanceOf(destinationId)).isEqualTo(10_00L);
    assertThat(creditsMinusDebits(destinationId)).isEqualTo(10_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(creditsMinusDebits(destinationId));
  }

  @Test
  void rejectedTransferLeavesBothSidesUntouched() {
    deposits.deposit(new DepositCommand(sourceId, 10_00L, "DOP"), "tr2-" + tag, sourceActor);
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 10_01L, "DOP"),
                    "tt-rej-" + tag,
                    sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    assertThat(balanceOf(sourceId)).isEqualTo(10_00L);
    assertThat(balanceOf(destinationId)).isZero();
    assertThat(debitCount(sourceId)).isZero();
    assertThat(creditCount(destinationId)).isZero();
    assertThat(creditsMinusDebits(sourceId)).isEqualTo(10_00L);
    assertThat(creditsMinusDebits(destinationId)).isZero();
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
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

  private Long debitCount(UUID accountId) {
    return jdbc.queryForObject(
        "select count(*) from movements where account_id = ? and direction = 'DEBIT'",
        Long.class,
        accountId);
  }

  private Long creditCount(UUID accountId) {
    return jdbc.queryForObject(
        "select count(*) from movements where account_id = ? and direction = 'CREDIT'",
        Long.class,
        accountId);
  }
}
