package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.users.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"debug=false","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO"})
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class UserManagementSessionTests {
 @Autowired WebApplicationContext context;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired UserManagementService service;
 MockMvc mvc;String admin;long adminId;List<Long> created=new ArrayList<>();
 void verify()throws Exception{try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings());}}
 @BeforeEach void setup()throws Exception{verify();mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();admin=jdbc.queryForObject("select username from app_user where role='ADMIN' order by id limit 1",String.class);adminId=jdbc.queryForObject("select id from app_user where username=?",Long.class,admin);}
 @AfterEach void cleanup()throws Exception{
  verify();jdbc.update("update app_user set active=true where id=?",adminId);
  for(long id:created)jdbc.update("update app_user set created_by=null,updated_by=null where id=?",id);
  for(long id:created)jdbc.update("delete from app_user where id=?",id); // Verified temporary test accounts only, never an application API.
 }
 JsonNode create(String role,String password)throws Exception{
  String name="session-"+UUID.randomUUID();var result=manage("POST","",Map.of("username",name,"role",role,"password",password));created.add(result.get("id").asLong());return result;
 }
 JsonNode manage(String method,String suffix,Object body)throws Exception{return json.readTree(mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),"/api/admin/users"+suffix).with(user(admin).roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());}
 MockHttpSession login(String username,String password)throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password",password)).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);}
 @Test void committedPasswordRoleAndStatusChangesExpireExistingSessions()throws Exception {
  String old=UUID.randomUUID().toString(),fresh=UUID.randomUUID().toString();var target=create("STAFF",old);long id=target.get("id").asLong();String name=target.get("username").asText();var session=login(name,old);
  mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
  target=manage("PUT","/"+id+"/password",Map.of("password",fresh,"version",target.get("version").asLong()));mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password",old)).andExpect(status().isUnauthorized());session=login(name,fresh);
  target=manage("PUT","/"+id+"/role",Map.of("role","ADMIN","version",target.get("version").asLong()));mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());session=login(name,fresh);mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isOk());
  target=manage("PATCH","/"+id+"/status",Map.of("active",false,"version",target.get("version").asLong()));mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password",fresh)).andExpect(status().isUnauthorized());target=manage("PATCH","/"+id+"/status",Map.of("active",true,"version",target.get("version").asLong()));session=login(name,fresh);
  manage("PUT","/"+id+"/role",Map.of("role","STAFF","version",target.get("version").asLong()));mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isUnauthorized());
  session=login(name,fresh);mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
 }
 @Test void concurrentCrossDeactivationCannotRemoveBothRemainingAdmins()throws Exception {
  var a=create("ADMIN",UUID.randomUUID().toString());var b=create("ADMIN",UUID.randomUUID().toString());jdbc.update("update app_user set active=false where id=?",adminId);
  var gate=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
  try {
   var first=executor.submit(()->deactivate(gate,a,b));var second=executor.submit(()->deactivate(gate,b,a));gate.countDown();
   int successes=(first.get(20,TimeUnit.SECONDS)?1:0)+(second.get(20,TimeUnit.SECONDS)?1:0);assertThat(successes).isEqualTo(1);
   assertThat(jdbc.queryForObject("select count(*) from app_user where active and role='ADMIN'",Integer.class)).isEqualTo(1);
  } finally {executor.shutdownNow();jdbc.update("update app_user set active=true where id=?",adminId);}
 }

 @Test void concurrentCaseVariantCreationProducesOnlyOneAccount()throws Exception {
  String name="Concurrent-"+UUID.randomUUID();var gate=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
  try {
   var a=executor.submit(()->createVariant(gate,name));var b=executor.submit(()->createVariant(gate,name.toLowerCase(Locale.ROOT)));gate.countDown();
   assertThat((a.get(20,TimeUnit.SECONDS)?1:0)+(b.get(20,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
   created.addAll(jdbc.queryForList("select id from app_user where lower(username)=lower(?)",Long.class,name));assertThat(created).hasSize(1);
  } finally {executor.shutdownNow();}
 }
 boolean createVariant(CountDownLatch gate,String name)throws Exception {
  gate.await();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  try{service.create(new UserData.Create(name,UUID.randomUUID().toString(),UserData.Role.STAFF),admin);return true;}
  catch(org.springframework.web.server.ResponseStatusException e){assertThat(e.getStatusCode().value()).isEqualTo(409);return false;}
  finally{SecurityContextHolder.clearContext();}
 }
 boolean deactivate(CountDownLatch gate,JsonNode actor,JsonNode target)throws Exception {
  gate.await();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.get("username").asText(),null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  try {service.status(target.get("id").asLong(),new UserData.Status(false,target.get("version").asLong()),actor.get("username").asText());return true;}
  catch(org.springframework.web.server.ResponseStatusException e){assertThat(e.getStatusCode().value()).isIn(400,403,409);return false;}
  finally {SecurityContextHolder.clearContext();}
 }
}
