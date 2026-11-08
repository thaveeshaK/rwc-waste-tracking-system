package au.org.resourcework.waste_tracking_api;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class UserManagementMigrationTests {
 @Test void populatedV17UpgradesWithoutChangingAppliedChecksumsOrHistoricalWeights()throws Exception {
  var settings=IsolatedTestDatabase.settings();try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())){IsolatedTestDatabase.verify(c,settings);String schema="users_upgrade_"+UUID.randomUUID().toString().replace("-","");var before=flyway(settings,schema,"17");before.migrate();c.setSchema(schema);Map<String,Integer> hashes=hashes(c);
   try(var s=c.createStatement()){s.executeUpdate("insert into app_user values (1,'migration-admin','unused','ADMIN')");s.executeUpdate("insert into whitegoods_record(submission_id,record_date,whitegoods_count,kg_per_item,estimated_weight,created_by,updated_by,created_at,updated_at) values ('"+UUID.randomUUID()+"','2005-01-01',2,48,96,1,1,now(),now())");}
   var after=flyway(settings,schema,"18");assertThat(after.migrate().migrationsExecuted).isEqualTo(1);after.validate();assertThat(hashes(c)).containsAllEntriesOf(hashes);
   try(var s=c.createStatement();var rs=s.executeQuery("select estimated_weight from whitegoods_record")){assertThat(rs.next()).isTrue();assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("96.00");}
  }
 }
 @Test void freshV1ThroughV18CreatesManagementColumns()throws Exception {
  var settings=IsolatedTestDatabase.settings();try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())){IsolatedTestDatabase.verify(c,settings);String schema="users_fresh_"+UUID.randomUUID().toString().replace("-","");var f=flyway(settings,schema,"18");assertThat(f.migrate().migrationsExecuted).isEqualTo(18);f.validate();c.setSchema(schema);assertThat(hashes(c)).hasSize(18);try(var s=c.createStatement();var rs=s.executeQuery("select count(*) from app_user")){assertThat(rs.next()).isTrue();assertThat(rs.getInt(1)).isZero();}}
 }

 @Test void v18ToV19PreservesIdentitiesAndEnforcesCaseInsensitiveUniqueness()throws Exception {
  var settings=IsolatedTestDatabase.settings();try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())){
   IsolatedTestDatabase.verify(c,settings);String schema="users_ci_"+UUID.randomUUID().toString().replace("-","");flyway(settings,schema,"18").migrate();c.setSchema(schema);var applied=hashes(c);
   try(var stmt=c.createStatement()){stmt.executeUpdate("insert into app_user(username,password_hash,role) values ('Historic-Admin','unused','ADMIN')");}
   var latest=flyway(settings,schema,"19");assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();assertThat(hashes(c)).containsAllEntriesOf(applied);
   try(var stmt=c.createStatement()){assertThatThrownBy(()->stmt.executeUpdate("insert into app_user(username,password_hash,role) values ('historic-admin','unused','STAFF')")).isInstanceOf(SQLException.class);}
   try(var stmt=c.createStatement();var rs=stmt.executeQuery("select username,active from app_user")){assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("Historic-Admin");assertThat(rs.getBoolean(2)).isTrue();assertThat(rs.next()).isFalse();}
   String fresh="users_ci_fresh_"+UUID.randomUUID().toString().replace("-","");assertThat(flyway(settings,fresh,"19").migrate().migrationsExecuted).isEqualTo(19);
  }
 }
 @Test void v19RefusesLegacyCaseCollisionsWithoutMergingOrRenamingAccounts()throws Exception {
  var settings=IsolatedTestDatabase.settings();try(var c=DriverManager.getConnection(settings.url(),settings.username(),settings.password())){
   IsolatedTestDatabase.verify(c,settings);String schema="users_ci_conflict_"+UUID.randomUUID().toString().replace("-","");flyway(settings,schema,"18").migrate();c.setSchema(schema);
   try(var stmt=c.createStatement()){stmt.executeUpdate("insert into app_user(username,password_hash,role) values ('Existing','unused','ADMIN'),('existing','unused','STAFF')");}
   assertThatThrownBy(()->flyway(settings,schema,"19").migrate()).hasStackTraceContaining("Case-insensitive duplicate usernames exist");
   try(var stmt=c.createStatement();var rs=stmt.executeQuery("select count(*) from app_user where username in ('Existing','existing')")){assertThat(rs.next()).isTrue();assertThat(rs.getInt(1)).isEqualTo(2);}
   assertThat(hashes(c)).doesNotContainKey("19");
  }
 }
 private Flyway flyway(IsolatedTestDatabase.Settings s,String schema,String target){return Flyway.configure().dataSource(s.url(),s.username(),s.password()).schemas(schema).defaultSchema(schema).cleanDisabled(true).target(target).load();}
 private Map<String,Integer> hashes(Connection c)throws Exception{var result=new HashMap<String,Integer>();try(var s=c.createStatement();var rs=s.executeQuery("select version,checksum from flyway_schema_history where success and version is not null")){while(rs.next())result.put(rs.getString(1),rs.getInt(2));}return result;}
}
