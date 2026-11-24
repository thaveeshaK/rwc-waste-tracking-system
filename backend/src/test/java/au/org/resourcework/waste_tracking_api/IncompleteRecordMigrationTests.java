package au.org.resourcework.waste_tracking_api;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class IncompleteRecordMigrationTests {
    @Test void populatedV21BackfillsActiveAndDeletedRowsWithoutChangingSnapshotsAuditOrChecksums() throws Exception {
        var settings=IsolatedTestDatabase.settings();String schema="incomplete_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(c,settings);flyway(settings,schema,"21").migrate();c.setSchema(schema);
            var hashes=hashes(c);var before=new HashMap<String,String>();
            try(var st=c.createStatement()) {
                st.executeUpdate("insert into app_user(username,password_hash,role) values ('status-migration-admin','unused','ADMIN')");
                for(String table:List.of("crate_pallet_record","electrical_crate_pallet_record")) {
                    st.executeUpdate("insert into "+table+"(submission_id,record_date,container_type_id,gross_weight,tare_weight,net_weight,created_by,updated_by,created_at,updated_at) select md5(id::text)::uuid,'2005-01-01',id,tare_weight+10,tare_weight,10,(select id from app_user limit 1),(select id from app_user limit 1),'2005-01-01T00:00:00Z','2005-01-01T00:00:00Z' from container_type where name='Stillage Cage'");
                    st.executeUpdate("update "+table+" set deleted_at='2005-02-01',deleted_by=created_by where id=(select min(id) from "+table+")");
                    // A second active historical record must also be backfilled.
                    st.executeUpdate("insert into "+table+"(submission_id,record_date,container_type_id,gross_weight,tare_weight,net_weight,created_by,updated_by,created_at,updated_at) select md5((id+100)::text)::uuid,'2005-01-02',container_type_id,gross_weight,tare_weight,net_weight,created_by,updated_by,created_at,updated_at from "+table);
                    before.put(table,rows(c,table));
                }
            }
            var latest=flyway(settings,schema,"22");assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
            assertThat(hashes(c)).containsAllEntriesOf(hashes);
            for(String table:before.keySet()) {
                assertThat(rows(c,table)).isEqualTo(before.get(table));
                try(var st=c.createStatement();var rs=st.executeQuery("select count(*) from "+table+" where status='COMPLETED'")) {rs.next();assertThat(rs.getInt(1)).isEqualTo(2);}
                assertThatThrownBy(()->{try(var st=c.createStatement()){st.executeUpdate("update "+table+" set status=null");}}).isInstanceOf(SQLException.class);
                assertThatThrownBy(()->{try(var st=c.createStatement()){st.executeUpdate("update "+table+" set status='INVALID'");}}).isInstanceOf(SQLException.class);
                try(var st=c.createStatement()) {assertThat(st.executeUpdate("update "+table+" set status='INCOMPLETE'")).isEqualTo(2);}
            }
        }
    }
    @Test void freshV1ThroughV22MigratesAndValidates() throws Exception {
        var settings=IsolatedTestDatabase.settings();
        try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())) {
            IsolatedTestDatabase.verify(c,settings);var f=flyway(settings,"incomplete_fresh_"+UUID.randomUUID().toString().replace("-",""),"22");
            assertThat(f.migrate().migrationsExecuted).isEqualTo(22);f.validate();
        }
    }
    private String rows(Connection c,String table) throws SQLException {
        try(var st=c.createStatement();var rs=st.executeQuery("select string_agg((to_jsonb(r)-'status')::text,'|' order by id) from "+table+" r")) {rs.next();return rs.getString(1);}
    }
    private Map<String,Integer> hashes(Connection c) throws SQLException {
        var result=new HashMap<String,Integer>();try(var st=c.createStatement();var rs=st.executeQuery("select version,checksum from flyway_schema_history where success and version is not null")) {while(rs.next())result.put(rs.getString(1),rs.getInt(2));}return result;
    }
    private Flyway flyway(IsolatedTestDatabase.Settings settings,String schema,String target) {
        return Flyway.configure().dataSource(settings.url(),settings.username(),settings.password()).schemas(schema).defaultSchema(schema).target(target).cleanDisabled(true).load();
    }
}
