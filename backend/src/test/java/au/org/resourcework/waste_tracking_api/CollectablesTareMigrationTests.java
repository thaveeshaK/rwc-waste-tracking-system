package au.org.resourcework.waste_tracking_api;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class CollectablesTareMigrationTests {
    @Test void populatedV22UpgradesWithoutChangingSnapshotsOrChecksums() throws Exception {
        var settings=IsolatedTestDatabase.settings();
        try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(c,settings);
            String schema="collectables_upgrade_"+UUID.randomUUID().toString().replace("-", "");
            flyway(settings,schema,"22").migrate();c.setSchema(schema);
            try(var s=c.createStatement()) {
                s.executeUpdate("insert into app_user(username,password_hash,role) values ('migration-admin','unused','ADMIN')");
                s.executeUpdate("insert into collectable_record(submission_id,record_date,gross_weight,tare_weight,net_weight,created_by,updated_by,created_at,updated_at) values ('"+UUID.randomUUID()+"','2001-01-01',10,2,8,(select min(id) from app_user),(select min(id) from app_user),now(),now())");
            }
            var hashes=hashes(c);String before=snapshot(c);
            var upgraded=flyway(settings,schema,"23");assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);upgraded.validate();
            assertThat(hashes(c)).containsAllEntriesOf(hashes);assertThat(snapshot(c)).isEqualTo(before);
            try(var s=c.createStatement();var r=s.executeQuery("select collectables_tub_tare from application_settings")) {r.next();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("2.00");}
            try(var s=c.createStatement()) {assertThat(s.executeUpdate("update collectable_record set gross_weight=1.50,tare_weight=0.50,net_weight=1.00")).isEqualTo(1);}
        }
    }
    @Test void freshV1ThroughV23MigratesAndValidates() throws Exception {
        var settings=IsolatedTestDatabase.settings();
        try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(c,settings);
            var f=flyway(settings,"collectables_fresh_"+UUID.randomUUID().toString().replace("-",""),"23");
            assertThat(f.migrate().migrationsExecuted).isEqualTo(23);f.validate();
        }
    }
    private String snapshot(Connection c)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery("select row_to_json(r)::text from collectable_record r")){r.next();return r.getString(1);}}
    private Map<String,Integer> hashes(Connection c)throws SQLException {var result=new HashMap<String,Integer>();try(var s=c.createStatement();var r=s.executeQuery("select version,checksum from flyway_schema_history where success and version is not null")){while(r.next())result.put(r.getString(1),r.getInt(2));}return result;}
    private Flyway flyway(IsolatedTestDatabase.Settings settings,String schema,String target) {return Flyway.configure().dataSource(settings.url(),settings.username(),settings.password()).schemas(schema).defaultSchema(schema).target(target).cleanDisabled(true).load();}
}
