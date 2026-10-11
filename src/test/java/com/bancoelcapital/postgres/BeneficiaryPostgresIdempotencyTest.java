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
import com.bancoelcapital.accounts.internal.AccountTransition;
import com.bancoelcapital.accounts.internal.AccountTransitionService;
import com.bancoelcapital.accounts.internal.TransitionCommand;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryCommand;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryException;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryResult;
import com.bancoelcapital.beneficiaries.internal.BeneficiaryService;
import com.bancoelcapital.customers.internal.CustomerCreationCommand;
import com.bancoelcapital.customers.internal.CustomerCreationService;
import com.bancoelcapital.identity.AuthenticatedActor;

/**
 * Beneficiary guarantees against real PostgreSQL: same key plus same payload replays without a
 * second row; same key plus different payload conflicts; duplicate destinations under different
 * keys reject deterministically; reads and deletes stay owner-scoped; audit evidence is recorded.
 */
class BeneficiaryPostgresIdempotencyTest extends AbstractPostgresTest {

  @Autowired BeneficiaryService beneficiaries;

  @Autowired CustomerCreationService customers;

  @Autowired AccountCreationService accounts;

  @Autowired AccountTransitionService transitions;

  @Autowired JdbcTemplate jdbc;

  UUID destinationId;
  AuthenticatedActor ownerActor;
  AuthenticatedActor otherActor;
  String tag;
  String owner;

  @BeforeEach
  void setup() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    owner = "holder-pgben-own-" + tag;
    String other = "holder-pgben-oth-" + tag;
    String destinationHolder = "holder-pgben-dst-" + tag;
    ownerActor = new AuthenticatedActor(owner, Set.of());
    otherActor = new AuthenticatedActor(other, Set.of());
    var destinationActor = new AuthenticatedActor(destinationHolder, Set.of());
    customers.create(new CustomerCreationCommand(owner, null), "c-" + tag, ownerActor);
    customers.create(new CustomerCreationCommand(other, null), "o-" + tag, otherActor);
    customers.create(
        new CustomerCreationCommand(destinationHolder, null), "d-" + tag, destinationActor);
    destinationId =
        accounts
            .create(
                new AccountCreationCommand(destinationHolder, "DOP", "BASIC"),
                "a-" + tag,
                destinationActor)
            .accountId();
  }

  @Test
  void sameKeySamePayloadReplaysWithoutSecondRow() {
    String key = "b-idem-" + tag;
    BeneficiaryResult first =
        beneficiaries.create(new BeneficiaryCommand(destinationId, "Ahorros"), key, ownerActor);
    BeneficiaryResult replayed =
        beneficiaries.create(new BeneficiaryCommand(destinationId, "  Ahorros "), key, ownerActor);

    assertThat(first.created()).isTrue();
    assertThat(replayed.created()).isFalse();
    assertThat(replayed.beneficiaryId()).isEqualTo(first.beneficiaryId());
    assertThat(beneficiaryCountForOwner()).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'BENEFICIARY_CREATED' and"
                    + " resource_id = ?",
                first.beneficiaryId().toString()))
        .isEqualTo(1);
  }

  @Test
  void sameKeyDifferentPayloadConflictsWithoutCreating() {
    String key = "b-cfl-" + tag;
    beneficiaries.create(new BeneficiaryCommand(destinationId, "Uno"), key, ownerActor);

    assertThatThrownBy(
            () ->
                beneficiaries.create(new BeneficiaryCommand(destinationId, "Dos"), key, ownerActor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.CONFLICT);

    assertThat(beneficiaryCountForOwner()).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit_events where action = 'BENEFICIARY_CONFLICT' and"
                    + " resource_id = ?",
                key))
        .isEqualTo(1);
  }

  @Test
  void duplicateDestinationUnderAnotherKeyRejectsDeterministically() {
    beneficiaries.create(new BeneficiaryCommand(destinationId, "Uno"), "b-d1-" + tag, ownerActor);

    assertThatThrownBy(
            () ->
                beneficiaries.create(
                    new BeneficiaryCommand(destinationId, "Dos"), "b-d2-" + tag, ownerActor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.REJECTED);

    assertThat(beneficiaryCountForOwner()).isEqualTo(1);
  }

  @Test
  void unknownDestinationRejectsWithoutRow() {
    assertThatThrownBy(
            () ->
                beneficiaries.create(
                    new BeneficiaryCommand(UUID.randomUUID(), "Nadie"), "b-unk-" + tag, ownerActor))
        .isInstanceOf(BeneficiaryException.class)
        .matches(e -> ((BeneficiaryException) e).getKind() == BeneficiaryException.Kind.REJECTED);

    assertThat(beneficiaryCountForOwner()).isZero();
  }

  @Test
  void blockedDestinationRemainsSavable() {
    // Operability is observed at financial-operation time, never as a save-time gate (ADR-20).
    var employee = new AuthenticatedActor("employee-" + tag, Set.of("BANK_EMPLOYEE"));
    transitions.transition(
        new TransitionCommand(destinationId, AccountTransition.BLOCK), "blk-" + tag, employee);

    String key = "b-blk-" + tag;
    BeneficiaryResult result =
        beneficiaries.create(new BeneficiaryCommand(destinationId, "Bloqueada"), key, ownerActor);

    assertThat(result.created()).isTrue();
    assertThat(beneficiaryCountForOwner()).isEqualTo(1);
  }

  @Test
  void readsAndDeletesStayOwnerScoped() {
    UUID id =
        beneficiaries
            .create(new BeneficiaryCommand(destinationId, "Mia"), "b-own-" + tag, ownerActor)
            .beneficiaryId();

    assertThat(beneficiaries.findForOwner(id, otherActor)).isEmpty();
    assertThat(beneficiaries.listForOwner(otherActor)).isEmpty();
    assertThat(beneficiaries.listForOwner(ownerActor)).hasSize(1);
    assertThat(beneficiaries.remove(id, otherActor)).isFalse();
    assertThat(beneficiaryCountForOwner()).isEqualTo(1);

    assertThat(beneficiaries.remove(id, ownerActor)).isTrue();
    assertThat(beneficiaryCountForOwner()).isZero();
    assertThat(beneficiaries.remove(id, ownerActor)).isFalse();
    assertThat(
            count(
                "select count(*) from audit_events where action = 'BENEFICIARY_REMOVED' and"
                    + " resource_id = ?",
                id.toString()))
        .isEqualTo(1);
  }

  /**
   * Owner-scoped count: the PostgreSQL container is shared across tests, so a global count would
   * accumulate rows from other tests. Each test uses a unique owner tag, keeping assertions
   * isolated without cross-test cleanup.
   */
  private int beneficiaryCountForOwner() {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from beneficiaries where owner_customer_id = ?", Integer.class, owner);
    return count == null ? 0 : count;
  }

  private int count(String sql, Object... args) {
    Integer count = jdbc.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }
}
