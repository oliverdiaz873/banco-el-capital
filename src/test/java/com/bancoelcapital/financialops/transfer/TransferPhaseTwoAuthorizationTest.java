package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.bancoelcapital.accounts.api.AccountLookupService;
import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.beneficiaries.api.BeneficiaryResolution;
import com.bancoelcapital.beneficiaries.api.BeneficiaryResolver;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryCommand;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * H-03 deterministic two-phase authorization proof on H2. Phase one runs before {@code writeTx};
 * phase two re-reads source/beneficiary and re-evaluates both decisions inside it. No sleeps: spies
 * change the second read only on a precise invocation count. PostgreSQL barriers remain in the
 * engine suite.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-phase-two;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferPhaseTwoAuthorizationTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired BeneficiaryService beneficiaries;

  @Autowired JdbcTemplate jdbc;

  @MockitoSpyBean AccountLookupService lookup;

  @MockitoSpyBean BeneficiaryResolver resolver;

  String tag;
  UUID sourceId;
  UUID destinationId;
  UUID alternateDestinationId;
  AuthenticatedActor sourceActor;
  AuthenticatedActor destinationActor;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    sourceActor = new AuthenticatedActor("holder-tp2-src-" + tag, Set.of());
    destinationActor = new AuthenticatedActor("holder-tp2-dst-" + tag, Set.of());
    customers.create(
        new CustomerCreationCommand(sourceActor.subject(), null), "c-tp2-" + tag, sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationActor.subject(), null),
        "d-tp2-" + tag,
        destinationActor);
    sourceId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "a-tp2-" + tag,
                sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationActor.subject(), "DOP", "BASIC"),
                "b-tp2-" + tag,
                destinationActor)
            .accountId();
    alternateDestinationId =
        accounts
            .create(
                new AccountCreationCommand(sourceActor.subject(), "DOP", "BASIC"),
                "c-tp2-" + tag,
                sourceActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tp2-" + tag, sourceActor);
  }

  @Test
  void sourceOwnershipChangeBetweenPhasesIsDeniedWithoutEffects() {
    String key = "tp2-source-change-" + tag;
    String changedHolder = "holder-tp2-other-" + tag;
    AtomicInteger sourceLookups = new AtomicInteger();
    doAnswer(
            invocation -> {
              UUID accountId = invocation.getArgument(0);
              if (sourceId.equals(accountId) && sourceLookups.incrementAndGet() == 2) {
                return Optional.of(changedHolder);
              }
              return invocation.callRealMethod();
            })
        .when(lookup)
        .findHolderCustomerId(any());

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    assertNoTransferEvidence(key);
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
    assertThat(balanceOf(alternateDestinationId)).isZero();
  }

  @Test
  void sourceOwnershipChangeOnSameAccountIntentIsDeniedWithoutRejectionEvidence() {
    String key = "tp2-source-change-same-account-" + tag;
    String changedHolder = "holder-tp2-other-" + tag;
    AtomicInteger sourceLookups = new AtomicInteger();
    doAnswer(
            invocation -> {
              UUID accountId = invocation.getArgument(0);
              if (sourceId.equals(accountId) && sourceLookups.incrementAndGet() == 2) {
                return Optional.of(changedHolder);
              }
              return invocation.callRealMethod();
            })
        .when(lookup)
        .findHolderCustomerId(any());

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(sourceId, sourceId, 40_00L, "DOP"), key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    assertNoTransferEvidence(key);
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
  }

  @Test
  void beneficiaryOwnerChangeBetweenPhasesIsDeniedWithoutEffects() {
    UUID beneficiaryId = saveBeneficiary("tp2-owner-change");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    String key = "tp2-owner-change-use-" + tag;
    String changedOwner = "holder-tp2-other-" + tag;
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                return Optional.of(
                    new BeneficiaryResolution(
                        beneficiaryId, changedOwner, destinationId, "DOP", "Ahorros"));
              }
              return invocation.callRealMethod();
            })
        .when(resolver)
        .resolveForOwner(any(), any());

    assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.FORBIDDEN);

    assertNoTransferEvidence(key);
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
  }

  @Test
  void beneficiaryDestinationChangeAfterLocksRollsBackAndPersistsBusinessRejection() {
    UUID beneficiaryId = saveBeneficiary("tp2-destination-change");
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");
    String key = "tp2-destination-change-use-" + tag;
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                return Optional.of(
                    new BeneficiaryResolution(
                        beneficiaryId,
                        sourceActor.subject(),
                        alternateDestinationId,
                        "DOP",
                        "Ahorros"));
              }
              return invocation.callRealMethod();
            })
        .when(resolver)
        .resolveForOwner(any(), any());

    assertThatThrownBy(() -> transfers.transfer(command, key, sourceActor))
        .isInstanceOf(TransferOperationException.class)
        .matches(
            e ->
                ((TransferOperationException) e).getKind()
                    == TransferOperationException.Kind.REJECTED);

    var row = intentRow(key);
    assertThat(row.get("outcome")).isEqualTo("REJECTED");
    assertThat(row.get("beneficiary_id")).isEqualTo(beneficiaryId);
    assertThat(row.get("path")).isEqualTo("beneficiary-transfer");
    assertThat(operationMovements(row.get("operation_id"))).isZero();
    assertThat(
            count(
                "select count(*) from transfer_beneficiary_usage where operation_id = ?",
                row.get("operation_id")))
        .isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'TRANSFER_REJECTED'"
                    + " and resource_id = ?",
                key))
        .isEqualTo(1);
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
    assertThat(balanceOf(alternateDestinationId)).isZero();
  }

  @Test
  void validTransferStillConfirmsAfterBothAuthorizations() {
    String key = "tp2-valid-" + tag;
    TransferResult result =
        transfers.transfer(
            new TransferCommand(sourceId, destinationId, 40_00L, "DOP"), key, sourceActor);

    assertThat(result.created()).isTrue();
    assertThat(intentRow(key).get("outcome")).isEqualTo("CONFIRMED");
    assertThat(operationMovements(result.operationId())).isEqualTo(2);
    assertThat(balanceOf(sourceId)).isEqualTo(60_00L);
    assertThat(balanceOf(destinationId)).isEqualTo(40_00L);
  }

  private UUID saveBeneficiary(String key) {
    return beneficiaries
        .create(new BeneficiaryCommand(destinationId, "Ahorros"), key + "-" + tag, sourceActor)
        .beneficiaryId();
  }

  private void assertNoTransferEvidence(String key) {
    assertThat(
            count(
                "select count(*) from transfer_operation_idempotency where idempotency_key = ?",
                key))
        .isZero();
    assertThat(
            count(
                "select count(*) from financial_operations where operation_type = 'TRANSFER'"
                    + " and account_id = ?",
                sourceId))
        .isZero();
    assertThat(
            count(
                "select count(*) from audit_events where action like 'TRANSFER_%'"
                    + " and resource_id = ?",
                key))
        .isZero();
  }

  private java.util.Map<String, Object> intentRow(String key) {
    return jdbc.queryForMap(
        "select operation_id, outcome, beneficiary_id, path"
            + " from transfer_operation_idempotency where idempotency_key = ?",
        key);
  }

  private int operationMovements(Object operationId) {
    Integer total =
        jdbc.queryForObject(
            "select count(*) from movements where operation_id = ?", Integer.class, operationId);
    return total == null ? 0 : total;
  }

  private int count(String sql, Object... args) {
    Integer total = jdbc.queryForObject(sql, Integer.class, args);
    return total == null ? 0 : total;
  }

  private Long balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance_minor_units from accounts where id = ?", Long.class, accountId);
  }
}
