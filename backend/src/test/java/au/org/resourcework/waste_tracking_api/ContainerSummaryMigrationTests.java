package au.org.resourcework.waste_tracking_api;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class ContainerSummaryMigrationTests {
    @Test void populatedV19UpgradesAndBackfillsExactHistoricalNamesWithoutChangingOldChecksums() throws Exception {
        var settings=IsolatedTestDatabase.settings();
        try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(c,settings);
            String schema="container_groups_"+UUID.randomUUID().toString().replace("-","");
            flyway(settings,schema,"19").migrate();c.setSchema(schema);
            var hashes=hashes(c);
            try(var stmt=c.createStatement()) {
                stmt.executeUpdate("insert into app_user(username,password_hash,role) values ('migration-admin','unused','ADMIN')");
                stmt.executeUpdate("insert into container_type(name,tare_weight) values ('Custom Pallet name',7)");
                stmt.executeUpdate("insert into crate_pallet_record(submission_id,record_date,container_type_id,gross_weight,tare_weight,net_weight,created_by,updated_by,created_at,updated_at) select md5(id::text)::uuid,'2005-01-01',id,tare_weight+10,tare_weight,10,(select id from app_user limit 1),(select id from app_user limit 1),now(),now() from container_type");
                // Historical classification must use the name snapshot, not current reference settings.
                stmt.executeUpdate("update container_type set name='Renamed historic crate' where name='Apple Crate'");
            }
            var latest=flyway(settings,schema,"20");assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
            assertThat(hashes(c)).containsAllEntriesOf(hashes);
            var groups=new HashMap<String,String>();
            try(var stmt=c.createStatement();var rs=stmt.executeQuery("select container_name,summary_group_snapshot from crate_pallet_record")) {while(rs.next())groups.put(rs.getString(1),rs.getString(2));}
            assertThat(groups).containsEntry("Apple Crate","CRATE").containsEntry("Stillage Cage","CRATE")
                .containsEntry("Plastic Pallet","PALLET").containsEntry("Pine Pallet","PALLET")
                .containsEntry("Hardwood Pallet","PALLET").containsEntry("Custom Pallet name","OTHER");
            try(var stmt=c.createStatement();var rs=stmt.executeQuery("select summary_group from container_type where name='Renamed historic crate'")) {rs.next();assertThat(rs.getString(1)).isEqualTo("OTHER");}
            try(var stmt=c.createStatement();var rs=stmt.executeQuery("select sum(net_weight) from crate_pallet_record")) {rs.next();assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("60");}
        }
    }
    @Test void freshV1ThroughV20MigratesAndValidates() throws Exception {
        var settings=IsolatedTestDatabase.settings();
        try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(c,settings);
            var f=flyway(settings,"container_fresh_"+UUID.randomUUID().toString().replace("-",""),"20");
            assertThat(f.migrate().migrationsExecuted).isEqualTo(20);f.validate();
        }
    }
    private Flyway flyway(IsolatedTestDatabase.Settings s,String schema,String target) {
        return Flyway.configure().dataSource(s.url(),s.username(),s.password()).schemas(schema).defaultSchema(schema).cleanDisabled(true).target(target).load();
    }
    private Map<String,Integer> hashes(Connection c) throws Exception {
        var result=new HashMap<String,Integer>();
        try(var stmt=c.createStatement();var rs=stmt.executeQuery("select version,checksum from flyway_schema_history where success and version is not null")) {while(rs.next())result.put(rs.getString(1),rs.getInt(2));}
        return result;
    }
}
