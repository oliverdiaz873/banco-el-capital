package com.bancoelcapital.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * V12/V13 Flyway behaviour on real PostgreSQL. Each test owns a disposable schema. V12 is exercised
 * with an invalid legacy row (preflight fails closed, row untouched); V13 is exercised with legacy
 * direct rows that predate the destination column, proving no backfill and the intended NOT VALID
 * behaviour. The shared application schema is never cleaned or mutated by this class, and every
 * schema created here is dropped in {@link #dropCreatedSchemas()}.
 */
class TransferPostgresMigrationTest extends AbstractPostgresTest {

  private final Set<String> createdSchemas = new LinkedHashSet<>();

  @AfterEach
  void dropCreatedSchemas() throws Exception {
    try (Connection connection = DriverManager.getConnection(jdbcUrl(), username(), password())) {
      for (String schema : createdSchemas) {
        try (var statement = connection.createStatement()) {
          statement.executeUpdate("drop schema if exists \"" + schema + "\" cascade");
        }
      }
    } finally {
      createdSchemas.clear();
    }
  }

  @Test
  void cleanSchemaAppliesV12AndRejectsConfirmedWithoutOperation() throws Exception {
    String schema = schemaName("clean");
    migrate(schema, null);

    try (Connection connection = connect(schema)) {
      assertThat(constraintCount(connection, schema, "ck_transfer_idem_confirmed_operation"))
          .isEqualTo(1);
      assertThatThrownBy(
              () ->
                  insertConfirmedWithoutOperation(
                      connection, "v12-clean-invalid", UUID.randomUUID()))
          .isInstanceOf(SQLException.class)
          .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
    }
  }

  @Test
  void v12PreflightFailsClosedWithoutRepairingTheInvalidRow() throws Exception {
    String schema = schemaName("preflight");
    migrate(schema, "11");

    try (Connection connection = connect(schema)) {
      insertConfirmedWithoutOperationAtV11(connection, "v12-preflight-invalid");
      assertThat(
              scalar(
                  connection,
                  "select count(*) from transfer_operation_idempotency"
                      + " where outcome = 'CONFIRMED' and operation_id is null"))
          .isEqualTo(1);
    }

    assertThatThrownBy(() -> migrate(schema, null))
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("V12 preflight failed");

    try (Connection connection = connect(schema)) {
      assertThat(
              scalar(
                  connection,
                  "select count(*) from transfer_operation_idempotency"
                      + " where outcome = 'CONFIRMED' and operation_id is null"))
          .isEqualTo(1);
      assertThat(constraintCount(connection, schema, "ck_transfer_idem_confirmed_operation"))
          .isZero();
      assertThat(
              scalar(connection, "select count(*) from flyway_schema_history where version = '12'"))
          .isZero();
    }

    // Test-fixture cleanup in the isolated schema only: the operator decision is simulated, never
    // automated against application data. V12 then applies and the constraint is present.
    try (Connection connection = connect(schema)) {
      connection
          .createStatement()
          .executeUpdate(
              "delete from transfer_operation_idempotency where idempotency_key ="
                  + " 'v12-preflight-invalid'");
    }
    migrate(schema, null);
    try (Connection connection = connect(schema)) {
      assertThat(constraintCount(connection, schema, "ck_transfer_idem_confirmed_operation"))
          .isEqualTo(1);
    }
  }

  @Test
  void v13PreservesLegacyRowsAndEnforcesDestinationOnNewWritesWithoutBackfill() throws Exception {
    String schema = schemaName("v13_legacy");
    migrate(schema, "12");

    try (Connection connection = connect(schema)) {
      insertDirectWithoutDestination(connection, "v13-legacy-direct");
      insertHistoricalAllNull(connection, "v13-legacy-historical");
    }

    migrate(schema, null);

    try (Connection connection = connect(schema)) {
      assertThat(
              columnCount(
                  connection, schema, "transfer_operation_idempotency", "destination_account_id"))
          .isEqualTo(1);
      assertThat(constraintCount(connection, schema, "ck_transfer_idem_destination_scope"))
          .isEqualTo(1);
      assertThat(isValidated(connection, schema, "ck_transfer_idem_destination_scope")).isTrue();
      assertThat(constraintCount(connection, schema, "ck_transfer_idem_direct_destination"))
          .isEqualTo(1);
      assertThat(isValidated(connection, schema, "ck_transfer_idem_direct_destination")).isFalse();

      // No backfill: both pre-V13 rows still have no destination evidence.
      assertThat(
              scalar(
                  connection,
                  "select count(*) from transfer_operation_idempotency"
                      + " where idempotency_key in ('v13-legacy-direct', 'v13-legacy-historical')"
                      + " and destination_account_id is null"))
          .isEqualTo(2);

      // A new direct row without destination is rejected by the NOT VALID check.
      assertThatThrownBy(() -> insertDirectWithoutDestination(connection, "v13-new-invalid"))
          .isInstanceOf(SQLException.class)
          .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));

      // New direct and beneficiary rows remain writable with their correct evidence.
      insertDirectWithDestination(connection, "v13-new-valid", UUID.randomUUID());
      insertBeneficiaryWithoutDestination(connection, "v13-new-beneficiary", UUID.randomUUID());
      assertThat(
              scalar(
                  connection,
                  "select count(*) from transfer_operation_idempotency"
                      + " where idempotency_key in ('v13-new-valid', 'v13-new-beneficiary')"))
          .isEqualTo(2);
      assertThatThrownBy(
              () ->
                  insertBeneficiaryWithDestination(
                      connection, "v13-new-beneficiary-invalid", UUID.randomUUID()))
          .isInstanceOf(SQLException.class)
          .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));

      // NOT VALID does not exempt a legacy row from future updates: the check is enforced for
      // UPDATE as well, so pre-V13 direct rows cannot be modified without inventing evidence.
      assertThatThrownBy(() -> updateLegacyDirect(connection, "v13-legacy-direct"))
          .isInstanceOf(SQLException.class)
          .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
    }
  }

  private void insertConfirmedWithoutOperationAtV11(Connection connection, String key)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into transfer_operation_idempotency"
                + " (idempotency_key, operation_id, request_hash, outcome, source_account_id, path,"
                + " amount_minor_units, currency)"
                + " values (?, null, ?, 'CONFIRMED', ?, 'direct', ?, 'DOP')")) {
      statement.setString(1, key);
      statement.setString(2, "hash-" + key);
      statement.setObject(3, UUID.randomUUID());
      statement.setLong(4, 10_00L);
      statement.executeUpdate();
    }
  }

  private void insertConfirmedWithoutOperation(Connection connection, String key, UUID destination)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                + " request_hash, outcome, source_account_id, beneficiary_id,"
                + " destination_account_id, path, amount_minor_units, currency)"
                + " values (?, null, ?, 'CONFIRMED', ?, null, ?, 'direct', ?, 'DOP')")) {
      statement.setString(1, key);
      statement.setString(2, "hash-" + key);
      statement.setObject(3, UUID.randomUUID());
      statement.setObject(4, destination);
      statement.setLong(5, 10_00L);
      statement.executeUpdate();
    }
  }

  private void insertDirectWithoutDestination(Connection connection, String key) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                + " request_hash, outcome, source_account_id, beneficiary_id, path,"
                + " amount_minor_units, currency)"
                + " values (?, null, ?, 'REJECTED', ?, null, 'direct', ?, 'DOP')")) {
      statement.setString(1, key);
      statement.setString(2, "hash-" + key);
      statement.setObject(3, UUID.randomUUID());
      statement.setLong(4, 10_00L);
      statement.executeUpdate();
    }
  }

  private void insertDirectWithDestination(Connection connection, String key, UUID destination)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                + " request_hash, outcome, source_account_id, beneficiary_id,"
                + " destination_account_id, path, amount_minor_units, currency)"
                + " values (?, null, ?, 'REJECTED', ?, null, ?, 'direct', ?, 'DOP')")) {
      statement.setString(1, key);
      statement.setString(2, "hash-" + key);
      statement.setObject(3, UUID.randomUUID());
      statement.setObject(4, destination);
      statement.setLong(5, 10_00L);
      statement.executeUpdate();
    }
  }

  private void insertBeneficiaryWithoutDestination(
      Connection connection, String key, UUID beneficiary) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                + " request_hash, outcome, source_account_id, beneficiary_id, path,"
                + " amount_minor_units, currency)"
                + " values (?, null, ?, 'REJECTED', ?, ?, 'beneficiary-transfer', ?, 'DOP')")) {
      statement.setString(1, key);
      statement.setString(2, "hash-" + key);
      statement.setObject(3, UUID.randomUUID());
      statement.setObject(4, beneficiary);
      statement.setLong(5, 10_00L);
      statement.executeUpdate();
    }
  }

  private void insertBeneficiaryWithDestination(Connection connection, String key, UUID beneficiary)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                + " request_hash, outcome, source_account_id, beneficiary_id,"
                + " destination_account_id, path, amount_minor_units, currency)"
                + " values (?, null, ?, 'REJECTED', ?, ?, ?, 'beneficiary-transfer', ?, 'DOP')")) {
      statement.setString(1, key);
      statement.setString(2, "hash-" + key);
      statement.setObject(3, UUID.randomUUID());
      statement.setObject(4, beneficiary);
      statement.setObject(5, UUID.randomUUID());
      statement.setLong(6, 10_00L);
      statement.executeUpdate();
    }
  }

  private void insertHistoricalAllNull(Connection connection, String key) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into transfer_operation_idempotency (idempotency_key, operation_id,"
                + " request_hash, outcome, source_account_id, beneficiary_id, path,"
                + " amount_minor_units, currency)"
                + " values (?, null, ?, 'REJECTED', null, null, null, null, null)")) {
      statement.setString(1, key);
      statement.setString(2, "hash-" + key);
      statement.executeUpdate();
    }
  }

  private void updateLegacyDirect(Connection connection, String key) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "update transfer_operation_idempotency set amount_minor_units = amount_minor_units + 1"
                + " where idempotency_key = ?")) {
      statement.setString(1, key);
      statement.executeUpdate();
    }
  }

  private void migrate(String schema, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(jdbcUrl(), username(), password())
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema);
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    configuration.load().migrate();
  }

  private Connection connect(String schema) throws Exception {
    Connection connection = DriverManager.getConnection(jdbcUrl(), username(), password());
    connection.setSchema(schema);
    return connection;
  }

  private int constraintCount(Connection connection, String schema, String name) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select count(*) from pg_constraint c"
                + " join pg_namespace n on n.oid = c.connamespace"
                + " where c.conname = ? and c.contype = 'c' and n.nspname = ?")) {
      statement.setString(1, name);
      statement.setString(2, schema);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private boolean isValidated(Connection connection, String schema, String name) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select c.convalidated from pg_constraint c"
                + " join pg_namespace n on n.oid = c.connamespace"
                + " where c.conname = ? and n.nspname = ?")) {
      statement.setString(1, name);
      statement.setString(2, schema);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private int columnCount(Connection connection, String schema, String table, String column)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select count(*) from information_schema.columns"
                + " where table_schema = ? and table_name = ? and column_name = ?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, column);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private int scalar(Connection connection, String sql) throws Exception {
    try (ResultSet result = connection.createStatement().executeQuery(sql)) {
      result.next();
      return result.getInt(1);
    }
  }

  private String schemaName(String suffix) {
    String schema = "phase43_" + suffix + "_" + UUID.randomUUID().toString().replace("-", "");
    createdSchemas.add(schema);
    return schema;
  }

  private String jdbcUrl() {
    return isExternalPostgresConfigured()
        ? System.getenv("BANCO_PG_JDBC_URL")
        : POSTGRES.getJdbcUrl();
  }

  private String username() {
    return isExternalPostgresConfigured() ? System.getenv("BANCO_PG_USER") : POSTGRES.getUsername();
  }

  private String password() {
    return isExternalPostgresConfigured()
        ? System.getenv("BANCO_PG_PASSWORD")
        : POSTGRES.getPassword();
  }
}
