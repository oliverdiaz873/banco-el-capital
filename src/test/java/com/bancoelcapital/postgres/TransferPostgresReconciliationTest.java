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
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.financialops.transfer.TransferCommand;
import com.bancoelcapital.financialops.transfer.TransferOperationException;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.financialops.withdrawal.WithdrawalCommand;
import com.bancoelcapital.financialops.withdrawal.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Reconciliation against real PostgreSQL: per account, the stored balance equals confirmed credits
 * minus confirmed debits over a mixed deposit/transfer/withdrawal history; rejected transfers leave
 * no movement behind.
 */
class TransferPostgresReconciliationTest extends AbstractPostgresTest {

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
    tag = UUID.randomUUID().toString().substring(0, 8);
    String sourceHolder = "holder-pgtr-src-" + tag;
    String destinationHolder = "holder-pgtr-dst-" + tag;
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    var destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "d-" + tag, destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceHolder, "DOP", "BASIC"), "a-" + tag, sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "b-" + tag,
                destinationActor)
            .accountId();
  }

  @Test
  void storedBalancesEqualCreditsMinusDebitsOnBothLegs() {
    deposits.deposit(new DepositCommand(sourceId, 40_00L, "DOP"), "wr-" + tag, sourceActor);
    transfers.transfer(
        new TransferCommand(sourceId, destinationId, 10_00L, "DOP"), "tt-" + tag, sourceActor);
    withdrawals.withdraw(new WithdrawalCommand(sourceId, 5_00L, "DOP"), "tw-" + tag, sourceActor);

    assertThat(balanceOf(sourceId)).isEqualTo(25_00L);
    assertThat(creditsMinusDebits(sourceId)).isEqualTo(25_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(10_00L);
    assertThat(creditsMinusDebits(destinationId)).isEqualTo(10_00L);
  }

  @Test
  void rejectedTransferIsExcludedFromMovements() {
    deposits.deposit(new DepositCommand(sourceId, 10_00L, "DOP"), "wr2-" + tag, sourceActor);
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
    assertThat(creditsMinusDebits(sourceId)).isEqualTo(10_00L);
    assertThat(creditsMinusDebits(destinationId)).isZero();
    assertThat(debitCount(sourceId)).isZero();
    assertThat(creditCount(destinationId)).isZero();
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

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
