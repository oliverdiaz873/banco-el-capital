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
import com.bancoelcapital.financialops.transfer.TransferResult;
import com.bancoelcapital.financialops.transfer.TransferService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Audit behavior against real PostgreSQL: confirmed, rejected and conflict outcomes each persist
 * exactly one event; replays record nothing new. Cleanup is scoped to TRANSFER_* actions only, so
 * concurrent suites sharing the container keep their own evidence.
 */
class TransferPostgresAuditTest extends AbstractPostgresTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

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
    String sourceHolder = "holder-pgta-src-" + tag;
    String destinationHolder = "holder-pgta-dst-" + tag;
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
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-" + tag, sourceActor);
    jdbc.execute("delete from audit_events where action like 'TRANSFER_%'");
  }

  @Test
  void confirmedTransferAuditsOnce() {
    TransferResult result =
        transfers.transfer(
            new TransferCommand(sourceId, destinationId, 40_00L, "DOP"),
            "t-ok-" + tag,
            sourceActor);

    assertThat(result.created()).isTrue();
    assertThat(auditCount("TRANSFER_CONFIRMED")).isEqualTo(1);
  }

  @Test
  void rejectedTransferAuditsRejectionOnly() {
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 999_00L, "DOP"),
                    "t-rej-" + tag,
                    sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    assertThat(auditCount("TRANSFER_REJECTED")).isEqualTo(1);
    assertThat(auditCount("TRANSFER_CONFIRMED")).isZero();
  }

  @Test
  void conflictAuditsOnceWithoutNewEffects() {
    String key = "t-cfl-" + tag;
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 999_00L, "DOP"), key, sourceActor))
        .isInstanceOf(TransferOperationException.class);
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 10_00L, "DOP"), key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.CONFLICT);

    assertThat(auditCount("TRANSFER_CONFLICT")).isEqualTo(1);
    assertThat(auditCount("TRANSFER_CONFIRMED")).isZero();
  }

  @Test
  void replaysRecordNoSecondAudit() {
    String confirmedKey = "t-rp-" + tag;
    transfers.transfer(
        new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), confirmedKey, sourceActor);
    transfers.transfer(
        new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), confirmedKey, sourceActor);

    String rejectedKey = "t-rpr-" + tag;
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 999_00L, "DOP"),
                    rejectedKey,
                    sourceActor))
        .isInstanceOf(TransferOperationException.class);
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 999_00L, "DOP"),
                    rejectedKey,
                    sourceActor))
        .isInstanceOf(TransferOperationException.class);

    assertThat(auditCount("TRANSFER_CONFIRMED")).isEqualTo(1);
    assertThat(auditCount("TRANSFER_REJECTED")).isEqualTo(1);
  }

  private int auditCount(String action) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from audit_events where action = ?", Integer.class, action);
    return count == null ? 0 : count;
  }
}
