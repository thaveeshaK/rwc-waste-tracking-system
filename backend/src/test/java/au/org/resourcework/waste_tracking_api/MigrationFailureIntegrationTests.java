package au.org.resourcework.waste_tracking_api;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class MigrationFailureIntegrationTests {
    @Test void DB_06_v21FailureRollsBackSchemaDataAndSuccessfulHistoryWithoutRepair() throws Exception {
        var settings=IsolatedTestDatabase.settings();
        try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(c,settings);
            String schema="migration_failure_"+UUID.randomUUID().toString().replace("-","");
            var baseline=Flyway.configure().dataSource(settings.url(),settings.username(),settings.password()).schemas(schema).defaultSchema(schema).target("20").cleanDisabled(true).load();
            baseline.migrate();c.setSchema(schema);
            try(var s=c.createStatement()) {
                s.executeUpdate("insert into app_user(username,password_hash,role) values ('rollback-admin','unused','ADMIN')");
                s.executeUpdate("insert into electrical_crate_pallet_record(submission_id,record_date,container_type_id,gross_weight,tare_weight,net_weight,created_by,updated_by,created_at,updated_at) select '"+UUID.randomUUID()+"','2001-01-01',id,tare_weight+10,tare_weight,10,(select min(id) from app_user),(select min(id) from app_user),now(),now() from container_type where name='Stillage Cage'");
                // Fail at V21's final CREATE TRIGGER, after its column/backfill/constraints.
                s.execute("create function controlled_noop() returns trigger language plpgsql as 'begin return NEW; end'");
                s.execute("create trigger electrical_crate_pallet_summary_group before update on electrical_crate_pallet_record for each row execute function controlled_noop()");
            }
            var history=rows(c,"select * from flyway_schema_history order by installed_rank");
            var records=rows(c,"select * from electrical_crate_pallet_record order by id");
            var columns=rows(c,"select column_name,data_type,is_nullable from information_schema.columns where table_schema=current_schema() and table_name='electrical_crate_pallet_record' order by ordinal_position");
            var latest=Flyway.configure().dataSource(settings.url(),settings.username(),settings.password()).schemas(schema).defaultSchema(schema).target("21").cleanDisabled(true).load();
            assertThatThrownBy(latest::migrate).isInstanceOf(FlywayException.class).hasStackTraceContaining("electrical_crate_pallet_summary_group");
            assertThat(rows(c,"select * from flyway_schema_history order by installed_rank")).isEqualTo(history);
            assertThat(rows(c,"select * from electrical_crate_pallet_record order by id")).isEqualTo(records);
            assertThat(rows(c,"select column_name,data_type,is_nullable from information_schema.columns where table_schema=current_schema() and table_name='electrical_crate_pallet_record' order by ordinal_position")).isEqualTo(columns);
            assertThat(rows(c,"select version from flyway_schema_history where version='21' and success")).isEmpty();
            baseline.validate();
            // Deliberately retain this unique schema until the isolated cluster is discarded.
        }
    }
    private List<List<Object>> rows(Connection c,String sql) throws SQLException {
        var rows=new ArrayList<List<Object>>();
        try(var s=c.createStatement();var r=s.executeQuery(sql)) {while(r.next()) {var row=new ArrayList<Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));rows.add(row);}}
        return rows;
    }
}
