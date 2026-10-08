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
import com.bancoelcapital.financialops.withdrawal.WithdrawalCommand;
import com.bancoelcapital.financialops.withdrawal.WithdrawalOperationException;
import com.bancoelcapital.financialops.withdrawal.WithdrawalResult;
import com.bancoelcapital.financialops.withdrawal.WithdrawalService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Idempotency guarantees against real PostgreSQL: same key plus same payload replays without a
 * second effect; same key plus different payload conflicts without moving money.
 */
class WithdrawalPostgresIdempotencyTest extends AbstractPostgresTest {

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
    String holder = "holder-pgid-" + tag;
    actor = new AuthenticatedActor(holder, Set.of());
    customers.create(new CustomerCreationCommand(holder, null), "c-" + tag, actor);
    var created =
        accounts.create(new AccountCreationCommand(holder, "DOP", "BASIC"), "a-" + tag, actor);
    accountId = created.accountId();
    deposits.deposit(new DepositCommand(accountId, 100_00L, "DOP"), "f-" + tag, actor);
  }

  @Test
  void sameKeySamePayloadReplaysWithoutSecondEffect() {
    String key = "w-idem-" + tag;
    WithdrawalResult first =
        withdrawals.withdraw(new WithdrawalCommand(accountId, 40_00L, "DOP"), key, actor);
    WithdrawalResult replayed =
        withdrawals.withdraw(new WithdrawalCommand(accountId, 40_00L, "DOP"), key, actor);

    assertThat(first.created()).isTrue();
    assertThat(replayed.created()).isFalse();
    assertThat(replayed.operationId()).isEqualTo(first.operationId());
    assertThat(debitCount()).isEqualTo(1);
    assertThat(balance()).isEqualTo(60_00L);
  }

  @Test
  void sameKeyDifferentPayloadConflictsWithoutMovingMoney() {
    String key = "w-cfl-" + tag;
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(new WithdrawalCommand(accountId, 999_00L, "DOP"), key, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.REJECTED);
    assertThatThrownBy(
            () -> withdrawals.withdraw(new WithdrawalCommand(accountId, 10_00L, "DOP"), key, actor))
        .isInstanceOf(WithdrawalOperationException.class)
        .matches(
            e ->
                ((WithdrawalOperationException) e).getKind()
                    == WithdrawalOperationException.Kind.CONFLICT);

    assertThat(debitCount()).isZero();
    assertThat(balance()).isEqualTo(100_00L);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'WITHDRAWAL_CONFLICT' and"
                    + " resource_id = ?",
                key))
        .isEqualTo(1);
  }

  private long debitCount() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from movements where account_id = ? and direction = 'DEBIT'",
            Long.class,
            accountId);
    return count == null ? 0 : count;
  }

  private int count(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }

  private Long balance() {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
