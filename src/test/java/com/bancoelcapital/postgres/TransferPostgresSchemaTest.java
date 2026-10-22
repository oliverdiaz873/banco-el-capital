package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Minimal PostgreSQL schema proof for Transfer foundation (ADR-18, ADR-21, Option A): Flyway V1-V13
 * apply cleanly and the transfer idempotency table exists as designed with no change to
 * financial_operations. Every catalog query is restricted to the current schema so isolated-schema
 * migration tests cannot contaminate the counts. Not a catalog test.
 */
class TransferPostgresSchemaTest extends AbstractPostgresTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayAppliedAllMigrationsIncludingTransfer() {
    Integer applied =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true", Integer.class);

    assertThat(applied).isGreaterThanOrEqualTo(13);
  }

  @Test
  void transferIdempotencyTableHasExpectedStructure() {
    assertThat(
            count(
                "select count(*) from information_schema.tables"
                    + " where table_schema = current_schema()"
                    + " and table_name = 'transfer_operation_idempotency'"))
        .isEqualTo(1);
    // PK on idempotency_key.
    assertThat(
            count(
                "select count(*) from information_schema.table_constraints"
                    + " where table_schema = current_schema()"
                    + " and table_name = 'transfer_operation_idempotency'"
                    + " and constraint_type = 'PRIMARY KEY'"))
        .isEqualTo(1);
    // FK operation_id -> financial_operations(id).
    assertThat(
            count(
                "select count(*) from information_schema.table_constraints"
                    + " where table_schema = current_schema()"
                    + " and table_name = 'transfer_operation_idempotency'"
                    + " and constraint_type = 'FOREIGN KEY'"))
        .isEqualTo(1);
    // operation_id is nullable by design (idempotency namespace convention).
    assertThat(
            jdbc.queryForObject(
                "select is_nullable from information_schema.columns"
                    + " where table_schema = current_schema()"
                    + " and table_name = 'transfer_operation_idempotency'"
                    + " and column_name = 'operation_id'",
                String.class))
        .isEqualTo("YES");
    // request_hash is VARCHAR(64) directly (V7 compatibility convention).
    assertThat(
            jdbc.queryForObject(
                "select data_type from information_schema.columns"
                    + " where table_schema = current_schema()"
                    + " and table_name = 'transfer_operation_idempotency'"
                    + " and column_name = 'request_hash'",
                String.class))
        .isEqualTo("character varying");
    // V12: CONFIRMED rows must reference their operation.
    assertThat(
            count(
                "select count(*) from pg_constraint"
                    + " where conname = 'ck_transfer_idem_confirmed_operation'"
                    + " and contype = 'c'"
                    + " and connamespace = current_schema()::regnamespace"))
        .isEqualTo(1);
  }

  @Test
  void v13DestinationColumnAndChecksHaveTheDocumentedValidationState() {
    assertThat(
            jdbc.queryForObject(
                "select data_type from information_schema.columns"
                    + " where table_schema = current_schema()"
                    + " and table_name = 'transfer_operation_idempotency'"
                    + " and column_name = 'destination_account_id'",
                String.class))
        .isEqualTo("uuid");
    assertThat(
            jdbc.queryForObject(
                "select is_nullable from information_schema.columns"
                    + " where table_schema = current_schema()"
                    + " and table_name = 'transfer_operation_idempotency'"
                    + " and column_name = 'destination_account_id'",
                String.class))
        .isEqualTo("YES");

    // Scope check is validated; the direct-required check is deliberately NOT VALID so legacy
    // direct rows survive without a backfill. NOT VALID does not exempt new writes or updates.
    assertThat(isValidated("ck_transfer_idem_destination_scope")).isTrue();
    assertThat(isValidated("ck_transfer_idem_direct_destination")).isFalse();
  }

  @Test
  void financialOperationsKeepsSingleAccountColumn() {
    // Option A: no destination_account_id column is introduced for Transfer MVP.
    assertThat(
            count(
                "select count(*) from information_schema.columns"
                    + " where table_schema = current_schema()"
                    + " and table_name = 'financial_operations'"
                    + " and column_name = 'destination_account_id'"))
        .isZero();
    assertThat(
            count(
                "select count(*) from information_schema.columns"
                    + " where table_schema = current_schema()"
                    + " and table_name = 'financial_operations'"
                    + " and column_name = 'account_id'"))
        .isEqualTo(1);
  }

  private int count(String sql) {
    Integer value = jdbc.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }

  private boolean isValidated(String constraint) {
    Boolean value =
        jdbc.queryForObject(
            "select convalidated from pg_constraint"
                + " where conname = ? and contype = 'c'"
                + " and connamespace = current_schema()::regnamespace",
            Boolean.class,
            constraint);
    return Boolean.TRUE.equals(value);
  }
}
