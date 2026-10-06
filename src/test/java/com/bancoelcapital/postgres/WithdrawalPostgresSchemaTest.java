package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Minimal PostgreSQL schema proof for Withdrawal: Flyway V1-V6 apply cleanly and the critical
 * structures (PK, FK, nullability, BIGINT money columns) exist as designed. Not a catalog test.
 */
class WithdrawalPostgresSchemaTest extends AbstractPostgresTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayAppliedAllMigrations() {
    Integer applied =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true", Integer.class);

    assertThat(applied).isGreaterThanOrEqualTo(6);
  }

  @Test
  void withdrawalIdempotencyTableHasExpectedStructure() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.tables"
                    + " where table_name = 'withdrawal_operation_idempotency'",
                Integer.class))
        .isEqualTo(1);
    // PK on idempotency_key.
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints"
                    + " where table_name = 'withdrawal_operation_idempotency'"
                    + " and constraint_type = 'PRIMARY KEY'",
                Integer.class))
        .isEqualTo(1);
    // FK operation_id -> financial_operations(id).
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints"
                    + " where table_name = 'withdrawal_operation_idempotency'"
                    + " and constraint_type = 'FOREIGN KEY'",
                Integer.class))
        .isEqualTo(1);
    // operation_id is nullable by design (ADR-16 namespace, V6 convention).
    assertThat(
            jdbc.queryForObject(
                "select is_nullable from information_schema.columns"
                    + " where table_name = 'withdrawal_operation_idempotency'"
                    + " and column_name = 'operation_id'",
                String.class))
        .isEqualTo("YES");
    // request_hash is VARCHAR(64) since V7 (JPA String mapping compatibility).
    assertThat(
            jdbc.queryForObject(
                "select data_type from information_schema.columns"
                    + " where table_name = 'withdrawal_operation_idempotency'"
                    + " and column_name = 'request_hash'",
                String.class))
        .isEqualTo("character varying");
  }

  @Test
  void moneyColumnsAreBigint() {
    assertThat(bigintColumn("accounts", "balance_minor_units")).isEqualTo("bigint");
    assertThat(bigintColumn("financial_operations", "amount_minor_units")).isEqualTo("bigint");
    assertThat(bigintColumn("movements", "amount_minor_units")).isEqualTo("bigint");
  }

  private String bigintColumn(String table, String column) {
    return jdbc.queryForObject(
        "select data_type from information_schema.columns"
            + " where table_name = ? and column_name = ?",
        String.class,
        table,
        column);
  }
}
