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
}
