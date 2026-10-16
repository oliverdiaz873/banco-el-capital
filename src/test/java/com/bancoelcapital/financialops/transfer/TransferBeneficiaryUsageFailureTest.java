package com.bancoelcapital.financialops.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bancoelcapital.accounts.internal.AccountCreationCommand;
import com.bancoelcapital.accounts.internal.AccountCreationService;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryCommand;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.financialops.deposit.DepositCommand;
import com.bancoelcapital.financialops.deposit.DepositService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * P2: a usage persistence failure must roll back the whole beneficiary confirmation — no operation,
 * no movements, no idempotency row, no usage row, no confirmation audit, balances intact. Mirrors
 * the {@code TransferAtomicityTest} failure-injection pattern.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:transfer-usage-failure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class TransferBeneficiaryUsageFailureTest {

  @Autowired TransferService transfers;

  @Autowired DepositService deposits;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired BeneficiaryService beneficiaries;

  @Autowired JdbcTemplate jdbc;

  @MockitoBean TransferBeneficiaryUsageRepository usage;

  UUID sourceId;
  UUID destinationId;
  AuthenticatedActor sourceActor;

  @BeforeEach
  void setup() {
    String sourceHolder = "holder-tuf-src";
    String destinationHolder = "holder-tuf-dst";
    sourceActor = new AuthenticatedActor(sourceHolder, Set.of());
    var destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(sourceHolder, null), "c-tuf", sourceActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "d-tuf", destinationActor);
    sourceId =
        accounts
            .create(new AccountCreationCommand(sourceHolder, "DOP", "BASIC"), "a-tuf", sourceActor)
            .accountId();
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "b-tuf",
                destinationActor)
            .accountId();
    deposits.deposit(new DepositCommand(sourceId, 100_00L, "DOP"), "f-tuf", sourceActor);
  }

  @Test
  void usageFailureRollsBackEntireConfirmation() {
    UUID beneficiaryId =
        beneficiaries
            .create(new BeneficiaryCommand(destinationId, "Ahorros"), "tuf-save", sourceActor)
            .beneficiaryId();
    doThrow(new RuntimeException("usage boom")).when(usage).save(any());
    TransferCommand command = new TransferCommand(sourceId, null, beneficiaryId, 40_00L, "DOP");

    assertThatThrownBy(() -> transfers.transfer(command, "tuf-confirm", sourceActor))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("usage boom");

    assertThat(count("select count(*) from financial_operations where operation_type = 'TRANSFER'"))
        .isZero();
    assertThat(
            count(
                "select count(*) from movements m join financial_operations o on o.id ="
                    + " m.operation_id where o.operation_type = 'TRANSFER'"))
        .isZero();
    assertThat(
            count(
                "select count(*) from transfer_operation_idempotency where idempotency_key = ?",
                "tuf-confirm"))
        .isZero();
    assertThat(count("select count(*) from transfer_beneficiary_usage")).isZero();
    assertThat(count("select count(*) from audit_events where action = 'TRANSFER_CONFIRMED'"))
        .isZero();
    assertThat(balanceOf(sourceId)).isEqualTo(100_00L);
    assertThat(balanceOf(destinationId)).isZero();
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
