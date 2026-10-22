package au.org.resourcework.waste_tracking_api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Separate schemas in the verified temporary database exercise real Flyway history without repair. */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class WhitegoodsMigrationTests {
    @Test
    void upgradesOriginalV11WithoutRepairAndPreservesExistingData() throws Exception {
        var settings = IsolatedTestDatabase.settings();
        try (var connection = verifiedConnection(settings)) {
            String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
            flyway(settings, schema, "11").migrate();
            connection.setSchema(schema);
            assertColumnType(connection, 12);
            assertOriginalChecksum(connection);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO app_user (id, username, password_hash, role) VALUES (1, 'migration-test', 'unused-test-value', 'ADMIN')");
                statement.executeUpdate("""
                    INSERT INTO whitegoods_record
                    (id, submission_id, version, record_date, whitegoods_count, kg_per_item, estimated_weight,
                     created_by, created_at, updated_by, updated_at)
                    VALUES (1, '11111111-1111-4111-8111-111111111111', 3, '2024-01-10', 5, 48.00, 240.00,
                            1, '2024-01-10T00:00:00Z', 1, '2024-01-11T00:00:00Z')
                    """);
            }

            IsolatedTestDatabase.verify(connection, settings);
            var upgraded = flyway(settings, schema, "12");
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            upgraded.validate();
            assertColumnType(connection, 14);
            assertOriginalChecksum(connection);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM whitegoods_record WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("whitegoods_count")).isEqualTo(5);
                assertThat(rows.getBigDecimal("estimated_weight")).isEqualByComparingTo("240.00");
                assertThat(rows.getBigDecimal("kg_per_item")).isEqualByComparingTo("48.00");
                assertThat(rows.getLong("version")).isEqualTo(3);
                assertThat(rows.getString("submission_id")).isEqualTo("11111111-1111-4111-8111-111111111111");
                assertThat(rows.getObject("record_date").toString()).isEqualTo("2024-01-10");
                assertThat(rows.getLong("created_by")).isEqualTo(1);
                assertThat(rows.getTimestamp("created_at").toInstant().toString()).isEqualTo("2024-01-10T00:00:00Z");
                assertThat(rows.getTimestamp("updated_at").toInstant().toString()).isEqualTo("2024-01-11T00:00:00Z");
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE whitegoods_record SET whitegoods_count=2147483647, estimated_weight=103079215056.00 WHERE id=1");
            }
        }
        // No teardown modifies data: the isolated runner stops this temporary PostgreSQL instance.
    }

    @Test
    void freshDatabaseSchemaMigratesThroughV12AndValidates() throws Exception {
        var settings = IsolatedTestDatabase.settings();
        try (var connection = verifiedConnection(settings)) {
            String schema = "fresh_" + UUID.randomUUID().toString().replace("-", "");
            var fresh = flyway(settings, schema, "12");
            assertThat(fresh.migrate().migrationsExecuted).isEqualTo(12);
            fresh.validate();
            connection.setSchema(schema);
            assertColumnType(connection, 14);
            assertOriginalChecksum(connection);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(12);
            }
        }
    }

    private Connection verifiedConnection(IsolatedTestDatabase.Settings settings) throws Exception {
        var connection = DriverManager.getConnection(settings.url(), settings.username(), settings.password());
        try {
            IsolatedTestDatabase.verify(connection, settings);
            return connection;
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    private Flyway flyway(IsolatedTestDatabase.Settings settings, String schema, String target) {
        return Flyway.configure().dataSource(settings.url(), settings.username(), settings.password())
                .locations("classpath:db/migration").schemas(schema).defaultSchema(schema)
                .cleanDisabled(true).target(target).load();
    }

    private void assertOriginalChecksum(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='11' AND success")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(-931850275);
        }
    }

    private void assertColumnType(Connection connection, int precision) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT numeric_precision, numeric_scale FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='whitegoods_record' AND column_name='estimated_weight'
                """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(precision);
            assertThat(rows.getInt(2)).isEqualTo(2);
        }
    }
}
