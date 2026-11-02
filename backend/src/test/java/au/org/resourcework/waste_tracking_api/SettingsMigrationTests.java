package au.org.resourcework.waste_tracking_api;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class SettingsMigrationTests {
    @Test void upgradesPopulatedV13AndBackfillsSnapshotsWithoutChangingHistory() throws Exception {
        var settings=IsolatedTestDatabase.settings();
        try(var connection=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(connection,settings);
            String schema="settings_upgrade_"+UUID.randomUUID().toString().replace("-","");
            Flyway.configure().dataSource(settings.url(),settings.username(),settings.password()).schemas(schema).defaultSchema(schema).cleanDisabled(true).target("13").load().migrate();
            connection.setSchema(schema);
            try(var s=connection.createStatement()) {
                s.executeUpdate("insert into app_user values (1,'migration-admin','unused','ADMIN')");
                for(String table:new String[]{"crate_pallet_record","electrical_crate_pallet_record"}) s.executeUpdate("insert into "+table+" (submission_id,record_date,container_type_id,gross_weight,tare_weight,net_weight,created_by,updated_by,created_at,updated_at) values ('"+UUID.randomUUID()+"','2006-01-01',(select id from container_type where name='Plastic Pallet'),16,6,10,1,1,'2006-01-01T00:00:00Z','2006-01-01T00:00:00Z')");
                s.executeUpdate("insert into whitegoods_record (submission_id,record_date,whitegoods_count,kg_per_item,estimated_weight,created_by,updated_by,created_at,updated_at) values ('"+UUID.randomUUID()+"','2006-01-01',2,48,96,1,1,now(),now())");
            }
            IsolatedTestDatabase.verify(connection,settings);
            var upgraded=Flyway.configure().dataSource(settings.url(),settings.username(),settings.password()).schemas(schema).defaultSchema(schema).cleanDisabled(true).target("14").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);upgraded.validate();
            try(var s=connection.createStatement()) {
                s.executeUpdate("update container_type set name='Renamed',tare_weight=9 where name='Plastic Pallet'");
                for(String table:new String[]{"crate_pallet_record","electrical_crate_pallet_record"}) try(var rows=s.executeQuery("select * from "+table)) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString("container_name")).isEqualTo("Plastic Pallet");assertThat(rows.getBigDecimal("tare_weight")).isEqualByComparingTo("6.00");assertThat(rows.getBigDecimal("net_weight")).isEqualByComparingTo("10.00");assertThat(rows.getTimestamp("updated_at").toInstant().toString()).isEqualTo("2006-01-01T00:00:00Z");
                }
                try(var rows=s.executeQuery("select kg_per_item,estimated_weight from whitegoods_record")) {assertThat(rows.next()).isTrue();assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("48.00");assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("96.00");}
            }
        }
        // No destructive teardown: the verified test runner owns and stops the temporary cluster.
    }
    @Test void upgradesPopulatedOriginalV14ToV15WithoutRepairOrDataChanges() throws Exception {
        var settings=IsolatedTestDatabase.settings();
        try(var connection=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(connection,settings);
            String schema="settings_v14_"+UUID.randomUUID().toString().replace("-","");
            var original=flyway(settings,schema,"14");
            original.migrate();original.validate();connection.setSchema(schema);
            assertOriginalV14(connection);
            assertPeriodLength(connection,20);
            try(var statement=connection.createStatement()) {
                statement.executeUpdate("insert into app_user values (1,'migration-admin','unused','ADMIN')");
                statement.executeUpdate("update application_settings set version=3,organisation_name='Existing organisation',site_name='Depot',report_display_name='Existing reports',display_unit='tonnes',financial_year_start_month=4,whitegoods_kg_per_item=52.25,updated_by=1,updated_at='2006-01-01T00:00:00Z'");
                for(String table:new String[]{"crate_pallet_record","electrical_crate_pallet_record"}) statement.executeUpdate("insert into "+table+" (submission_id,record_date,container_type_id,gross_weight,tare_weight,net_weight,created_by,updated_by,created_at,updated_at) values ('"+UUID.randomUUID()+"','2006-01-01',(select id from container_type where name='Plastic Pallet'),16,6,10,1,1,'2006-01-01T00:00:00Z','2006-01-01T00:00:00Z')");
                statement.executeUpdate("insert into whitegoods_record (submission_id,record_date,whitegoods_count,kg_per_item,estimated_weight,created_by,updated_by,created_at,updated_at) values ('"+UUID.randomUUID()+"','2006-01-01',2147483647,999.99,2147462172163.53,1,1,now(),now())");
            }
            var before=snapshot(connection);
            IsolatedTestDatabase.verify(connection,settings);
            var upgraded=flyway(settings,schema,"15");
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);upgraded.validate();
            assertOriginalV14(connection);assertPeriodLength(connection,32);
            assertThat(snapshot(connection)).isEqualTo(before);
            try(var statement=connection.createStatement()) {
                statement.executeUpdate("update application_settings set report_period='CURRENT_FINANCIAL_YEAR' where id=1");
                try(var rows=statement.executeQuery("select report_period,version from application_settings")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("CURRENT_FINANCIAL_YEAR");assertThat(rows.getLong(2)).isEqualTo(3);
                }
                try(var rows=statement.executeQuery("select numeric_precision,numeric_scale from information_schema.columns where table_schema=current_schema() and table_name='whitegoods_record' and column_name='estimated_weight'")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(15);assertThat(rows.getInt(2)).isEqualTo(2);
                }
            }
        }
    }

    @Test void freshSchemaMigratesV1ThroughV15AndRetainsSettingsDefaults() throws Exception {
        var settings=IsolatedTestDatabase.settings();
        try(var connection=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(connection,settings);
            String schema="settings_fresh_"+UUID.randomUUID().toString().replace("-","");
            var fresh=flyway(settings,schema,"15");
            assertThat(fresh.migrate().migrationsExecuted).isEqualTo(15);fresh.validate();connection.setSchema(schema);
            assertOriginalV14(connection);assertPeriodLength(connection,32);
            try(var statement=connection.createStatement();var rows=statement.executeQuery("select report_period,whitegoods_kg_per_item,financial_year_start_month from application_settings where id=1")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("CURRENT_MONTH");assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("48.00");assertThat(rows.getInt(3)).isEqualTo(7);
            }
        }
    }

    private Flyway flyway(IsolatedTestDatabase.Settings settings,String schema,String target) {
        return Flyway.configure().dataSource(settings.url(),settings.username(),settings.password()).schemas(schema).defaultSchema(schema).cleanDisabled(true).target(target).load();
    }
    private void assertOriginalV14(Connection connection) throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("select checksum from flyway_schema_history where version='14' and success")) {
            assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(-548343575);
        }
    }
    private void assertPeriodLength(Connection connection,int expected) throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("select character_maximum_length from information_schema.columns where table_schema=current_schema() and table_name='application_settings' and column_name='report_period'")) {
            assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(expected);
        }
    }
    private java.util.List<String> snapshot(Connection connection) throws Exception {
        var result=new java.util.ArrayList<String>();
        for(String table:new String[]{"application_settings","crate_pallet_record","electrical_crate_pallet_record","whitegoods_record"}) {
            try(var statement=connection.createStatement();var rows=statement.executeQuery("select row_to_json(r)::text from "+table+" r order by id")) {
                while(rows.next()) result.add(table+":"+rows.getString(1));
            }
        }
        return result;
    }

}
