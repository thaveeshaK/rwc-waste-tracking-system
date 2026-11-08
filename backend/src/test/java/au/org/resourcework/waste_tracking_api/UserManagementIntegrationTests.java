package au.org.resourcework.waste_tracking_api;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"debug=false","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO"})
@Transactional
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class UserManagementIntegrationTests {
 @Autowired WebApplicationContext context;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired Clock clock;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
 MockMvc mvc;String admin,staff;
 @BeforeEach void setup()throws Exception {
  try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings());}
  mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  admin=jdbc.queryForObject("select username from app_user where role='ADMIN' order by id limit 1",String.class);staff=jdbc.queryForObject("select username from app_user where role='STAFF' order by id limit 1",String.class);
 }
 String password(){return UUID.randomUUID().toString();}
 ResultActions send(String method,String path,Object body)throws Exception{return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),"/api/admin/users"+path).with(user(admin).roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)));}
 JsonNode create(String name,String role,String password)throws Exception{return json.readTree(send("POST","",Map.of("username",name,"role",role,"password",password)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder list(){return get("/api/admin/users").with(user(admin).roles("ADMIN"));}
 @Test void anonymousStaffAndCsrfBoundariesCoverAllEndpoints()throws Exception {
  var paths=Map.of("GET","","POST","","PUT","/1/role","PATCH","/1/status");
  for(var entry:paths.entrySet()){
   var method=org.springframework.http.HttpMethod.valueOf(entry.getKey());String url="/api/admin/users"+entry.getValue();
   mvc.perform(request(method,url).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
   mvc.perform(request(method,url).with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  }
  mvc.perform(put("/api/admin/users/1/password").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
  mvc.perform(put("/api/admin/users/1/password").with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/users").with(user(admin).roles("ADMIN")).contentType("application/json").content("{}")).andExpect(status().isForbidden());mvc.perform(list()).andExpect(status().isOk());
 }
 @Test void createBothRolesHashPasswordsTrimNamesAndReturnSafeDto()throws Exception {
  String pass=password();var created=create("  managed-staff  ","STAFF",pass);create("managed-admin","ADMIN",password());assertThat(created.get("username").asText()).isEqualTo("managed-staff");assertThat(created.toString()).doesNotContain("password",pass,"passwordHash");
  String hash=jdbc.queryForObject("select password_hash from app_user where id=?",String.class,created.get("id").asLong());assertThat(hash).isNotEqualTo(pass);assertThat(encoder.matches(pass,hash)).isTrue();assertThat(created.get("createdBy").asText()).isEqualTo(admin);
  mvc.perform(post("/api/auth/login").with(csrf()).param("username","managed-staff").param("password",pass)).andExpect(status().isNoContent());
 }
 @Test void invalidAndDuplicateInputsNeverCreateUsers()throws Exception {
  long count=jdbc.queryForObject("select count(*) from app_user",Long.class);
  for(String pass:List.of("","   ","x".repeat(73),"é".repeat(37)))send("POST","",Map.of("username","invalid-user","password",pass,"role","STAFF")).andExpect(status().isBadRequest());
  send("POST","",Map.of("username"," ","password",password(),"role","STAFF")).andExpect(status().isBadRequest());send("POST","",Map.of("username","missing-role","password",password())).andExpect(status().isBadRequest());
  assertThat(jdbc.queryForObject("select count(*) from app_user",Long.class)).isEqualTo(count);
  create("Duplicate","STAFF",password());send("POST","",Map.of("username"," Duplicate ","password",password(),"role","ADMIN")).andExpect(status().isConflict());
  send("POST","",Map.of("username","duplicate","password",password(),"role","STAFF")).andExpect(status().isConflict());
 }
 @Test void rolesCanChangeWithoutRenamingOrRewritingHistoricalOwnership()throws Exception {
  var row=create("historic-user","STAFF",password());long id=row.get("id").asLong();
  jdbc.update("insert into whitegoods_record(submission_id,record_date,whitegoods_count,kg_per_item,estimated_weight,created_by,updated_by,created_at,updated_at) values (?,date '2002-01-01',1,48,48,?,?,now(),now())",UUID.randomUUID(),id,id);
  send("PUT","/"+id+"/role",Map.of("role","ADMIN","version",row.get("version").asLong(),"username","renamed")).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("historic-user"));
  long version=jdbc.queryForObject("select version from app_user where id=?",Long.class,id);send("PUT","/"+id+"/role",Map.of("role","STAFF","version",version)).andExpect(status().isOk());
  version=jdbc.queryForObject("select version from app_user where id=?",Long.class,id);send("PATCH","/"+id+"/status",Map.of("active",false,"version",version)).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select u.username from whitegoods_record r join app_user u on u.id=r.created_by where r.created_by=?",String.class,id)).isEqualTo("historic-user");assertThat(jdbc.queryForObject("select updated_by from app_user where id=?",Long.class,id)).isEqualTo(jdbc.queryForObject("select id from app_user where username=?",Long.class,admin));
 }
 @Test void activationAndPasswordResetTakeEffectForNewLogins()throws Exception {
  String old=password(),fresh=password();var row=create("login-managed","STAFF",old);long id=row.get("id").asLong();long version=row.get("version").asLong();
  send("PATCH","/"+id+"/status",Map.of("active",false,"version",version)).andExpect(status().isOk());mvc.perform(post("/api/auth/login").with(csrf()).param("username","login-managed").param("password",old)).andExpect(status().isUnauthorized());
  version=jdbc.queryForObject("select version from app_user where id=?",Long.class,id);send("PATCH","/"+id+"/status",Map.of("active",true,"version",version)).andExpect(status().isOk());mvc.perform(post("/api/auth/login").with(csrf()).param("username","login-managed").param("password",old)).andExpect(status().isNoContent());
  version=jdbc.queryForObject("select version from app_user where id=?",Long.class,id);send("PUT","/"+id+"/password",Map.of("password",fresh,"version",version)).andExpect(status().isOk());
  mvc.perform(post("/api/auth/login").with(csrf()).param("username","login-managed").param("password",old)).andExpect(status().isUnauthorized());mvc.perform(post("/api/auth/login").with(csrf()).param("username","login-managed").param("password",fresh)).andExpect(status().isNoContent());
 }
 @Test void finalActiveAdminAndSelfProtectionAreEnforcedBackendSide()throws Exception {
  long id=jdbc.queryForObject("select id from app_user where username=?",Long.class,admin),version=jdbc.queryForObject("select version from app_user where id=?",Long.class,id);
  send("PATCH","/"+id+"/status",Map.of("active",false,"version",version)).andExpect(status().isBadRequest());send("PUT","/"+id+"/role",Map.of("role","STAFF","version",version)).andExpect(status().isBadRequest());send("PUT","/"+id+"/password",Map.of("password",password(),"version",version)).andExpect(status().isBadRequest());
  create("another-admin","ADMIN",password());send("PATCH","/"+id+"/status",Map.of("active",false,"version",version)).andExpect(status().isBadRequest());send("PUT","/"+id+"/role",Map.of("role","STAFF","version",version)).andExpect(status().isBadRequest());
 }
 @Test void versionsRejectStaleUserActionsAndIdsAreValidated()throws Exception {
  var row=create("stale-user","STAFF",password());long id=row.get("id").asLong(),version=row.get("version").asLong();send("PUT","/"+id+"/role",Map.of("role","ADMIN","version",version)).andExpect(status().isOk());send("PATCH","/"+id+"/status",Map.of("active",false,"version",version)).andExpect(status().isConflict());send("PUT","/999999999/role",Map.of("role","STAFF","version",0)).andExpect(status().isNotFound());
 }
 @Test void paginationUsesApprovedSizesCountsAndStableUsernameOrdering()throws Exception {
  long baseline=jdbc.queryForObject("select count(*) from app_user",Long.class);
  String hash=encoder.encode(password());for(int n=0;n<53;n++)jdbc.update("insert into app_user(username,password_hash,role) values (?,?,'STAFF')",String.format("page-user-%03d",n),hash);
  mvc.perform(list()).andExpect(jsonPath("$.size").value(10)).andExpect(jsonPath("$.items.length()").value(10)).andExpect(jsonPath("$.totalItems").value((int)baseline+53)).andExpect(jsonPath("$.totalPages").value((int)(baseline+62)/10));
  for(int size:List.of(25,50))mvc.perform(list().param("size",""+size)).andExpect(jsonPath("$.items.length()").value(size));
  for(String size:List.of("0","1","100"))mvc.perform(list().param("size",size)).andExpect(status().isBadRequest());
  var first=json.readTree(mvc.perform(list()).andReturn().getResponse().getContentAsString());var second=json.readTree(mvc.perform(list().param("page","1")).andReturn().getResponse().getContentAsString());assertThat(first.get("items").get(9).get("username").asText()).isLessThan(second.get("items").get(0).get("username").asText());
  assertThat(first.toString()).doesNotContain("password","passwordHash");
 }
 @Test void invalidRoleIsRejectedWithoutCreatingAnAccount()throws Exception {
  send("POST","",Map.of("username","bad-role","password",password(),"role","MANAGER")).andExpect(status().isBadRequest());
  assertThat(jdbc.queryForObject("select count(*) from app_user where username='bad-role'",Integer.class)).isZero();
 }
 @Test void usernameSearchIsCaseInsensitiveLiteralAndAppliedBeforePaging()throws Exception {
  String hash=encoder.encode(password());for(int n=0;n<31;n++)jdbc.update("insert into app_user(username,password_hash,role) values (?,?,'STAFF')",String.format("Search-User-%02d",n),hash);
  mvc.perform(list().param("query"," search-USER ")).andExpect(jsonPath("$.totalItems").value(31)).andExpect(jsonPath("$.totalPages").value(4)).andExpect(jsonPath("$.items.length()").value(10));
  mvc.perform(list().param("query","SEARCH-user").param("page","1")).andExpect(jsonPath("$.items[0].username").value("Search-User-10"));
  mvc.perform(list().param("query","Search-user").param("size","25")).andExpect(jsonPath("$.items.length()").value(25));
  mvc.perform(list().param("query","no-such-user").param("page","99")).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.totalItems").value(0));
  create("literal_%","STAFF",password());mvc.perform(list().param("query","_%")).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].username").value("literal_%"));
  mvc.perform(list().param("query","x".repeat(101))).andExpect(status().isBadRequest());
 }
 @Test void anotherAdminCanBeDeactivatedAndDemotedWhenAnActiveAdminRemains()throws Exception {
  var other=create("other-admin","ADMIN",password());long id=other.get("id").asLong();
  var inactive=send("PATCH","/"+id+"/status",Map.of("active",false,"version",other.get("version").asLong())).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
  long version=jdbc.queryForObject("select version from app_user where id=?",Long.class,id);
  send("PATCH","/"+id+"/status",Map.of("active",true,"version",version)).andExpect(status().isOk());
  version=jdbc.queryForObject("select version from app_user where id=?",Long.class,id);send("PUT","/"+id+"/role",Map.of("role","STAFF","version",version)).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select count(*) from app_user where active and role='ADMIN'",Integer.class)).isEqualTo(1);
 }

}
