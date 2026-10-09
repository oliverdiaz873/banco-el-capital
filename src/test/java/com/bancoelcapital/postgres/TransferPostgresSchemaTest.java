package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Minimal PostgreSQL schema proof for Transfer foundation (ADR-18, Phase 1, Option A): Flyway V1-V8
 * apply cleanly and the transfer idempotency table exists as designed with no change to
 * financial_operations. Not a catalog test.
 */
class TransferPostgresSchemaTest extends AbstractPostgresTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayAppliedAllMigrationsIncludingTransfer() {
    Integer applied =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true", Integer.class);

    assertThat(applied).isGreaterThanOrEqualTo(8);
  }

  @Test
  void transferIdempotencyTableHasExpectedStructure() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.tables"
                    + " where table_name = 'transfer_operation_idempotency'",
                Integer.class))
        .isEqualTo(1);
    // PK on idempotency_key.
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints"
                    + " where table_name = 'transfer_operation_idempotency'"
                    + " and constraint_type = 'PRIMARY KEY'",
                Integer.class))
        .isEqualTo(1);
    // FK operation_id -> financial_operations(id).
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints"
                    + " where table_name = 'transfer_operation_idempotency'"
                    + " and constraint_type = 'FOREIGN KEY'",
                Integer.class))
        .isEqualTo(1);
    // operation_id is nullable by design (idempotency namespace convention).
    assertThat(
            jdbc.queryForObject(
                "select is_nullable from information_schema.columns"
                    + " where table_name = 'transfer_operation_idempotency'"
                    + " and column_name = 'operation_id'",
                String.class))
        .isEqualTo("YES");
    // request_hash is VARCHAR(64) directly (V7 compatibility convention).
    assertThat(
            jdbc.queryForObject(
                "select data_type from information_schema.columns"
                    + " where table_name = 'transfer_operation_idempotency'"
                    + " and column_name = 'request_hash'",
                String.class))
        .isEqualTo("character varying");
  }

  @Test
  void financialOperationsKeepsSingleAccountColumn() {
    // Option A: no destination_account_id column is introduced for Transfer MVP.
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns"
                    + " where table_name = 'financial_operations'"
                    + " and column_name = 'destination_account_id'",
                Integer.class))
        .isEqualTo(0);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns"
                    + " where table_name = 'financial_operations'"
                    + " and column_name = 'account_id'",
                Integer.class))
        .isEqualTo(1);
  }
}
