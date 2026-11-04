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
class EwastePickupIntegrationTests {
 @Autowired WebApplicationContext context;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired Clock clock;
 MockMvc mvc;long plastic,pine;String staff,admin;
 @BeforeEach void setup() throws Exception {
  try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings());}
  mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  staff=jdbc.queryForObject("select username from app_user where role='STAFF' limit 1",String.class);admin=jdbc.queryForObject("select username from app_user where role='ADMIN' limit 1",String.class);
  plastic=jdbc.queryForObject("select id from container_type where name='Plastic Pallet'",Long.class);pine=jdbc.queryForObject("select id from container_type where name='Pine Pallet'",Long.class);
  for(long id:List.of(plastic,pine))jdbc.update("insert into container_type_applicability(context,container_type_id,active) values ('EWASTE_PICKUP',?,true) on conflict (context,container_type_id) do update set active=true",id);
 }
 Map<String,Object> input(String organisation,String weight,List<?> lines){var m=new HashMap<String,Object>();m.put("submissionId",UUID.randomUUID());m.put("recordDate","2005-04-10");m.put("organisation",organisation);m.put("enteredWeight",weight);m.put("containers",lines);return m;}
 Map<String,Object> line(long id,Object count){return Map.of("containerTypeId",id,"quantity",count);}
 ResultActions send(String path,Map<String,Object> body)throws Exception{return mvc.perform(post("/api/ewaste-pickups"+path).with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)));}
 JsonNode created(Map<String,Object> body)throws Exception{return json.readTree(send("",body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
 ResultActions update(long id,Map<String,Object> body)throws Exception{return mvc.perform(put("/api/ewaste-pickups/"+id).with(user(admin).roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)));}
 @Test void noContainersPreviewDoesNotSaveAndNetIsEnteredWeight()throws Exception {
  var body=input("  Next Band  ","370.12",List.of());
  send("/preview",body).andExpect(status().isOk()).andExpect(jsonPath("$.netWeight").value("370.12")).andExpect(jsonPath("$.totalTareWeight").value("0.00"));
  assertThat(jdbc.queryForObject("select count(*) from ewaste_pickup_record where submission_id=?",Integer.class,body.get("submissionId"))).isZero();
  var row=created(body);assertThat(row.get("organisation").asText()).isEqualTo("Next Band");assertThat(row.get("containers").size()).isZero();assertThat(row.get("createdBy").asText()).isEqualTo(staff);
 }
 @Test void multipleTypesAndQuantitiesUseExactTareSnapshots()throws Exception {
  var row=created(input("New source","100.55",List.of(line(plastic,2),line(pine,3))));
  assertThat(row.get("totalTareWeight").asText()).isEqualTo("66.00");assertThat(row.get("netWeight").asText()).isEqualTo("34.55");assertThat(row.get("containers").size()).isEqualTo(2);
  assertThat(row.get("containers").toString()).contains("Plastic Pallet","Pine Pallet","6.00","18.00","54.00");
 }
 @Test void invalidWeightsDatesOrganisationQuantitiesAndDuplicateLinesAreRejected()throws Exception {
  for(String weight:List.of("0","-1","1.001","100000000.00","6.00","5.99"))send("/preview",input("Source",weight,List.of(line(plastic,1)))).andExpect(status().isBadRequest());
  for(Object quantity:List.of(0,-1,"1.5","2147483648"))send("/preview",input("Source","100.00",List.of(line(plastic,quantity)))).andExpect(status().isBadRequest());
  send("/preview",input("  ","100.00",List.of())).andExpect(status().isBadRequest());
  send("/preview",input("Source","100.00",List.of(line(plastic,1),line(plastic,1)))).andExpect(status().isBadRequest());
  var future=input("Source","100",List.of());future.put("recordDate",LocalDate.now(clock).plusDays(1).toString());send("/preview",future).andExpect(status().isBadRequest());
  var missing=input("Source","100",List.of());missing.remove("enteredWeight");send("/preview",missing).andExpect(status().isBadRequest());
 }
 @Test void onlyActiveApplicableReferencesCanBeNewlySelected()throws Exception {
  long cage=jdbc.queryForObject("select id from container_type where name='Stillage Cage'",Long.class);
  send("/preview",input("Source","100",List.of(line(cage,1)))).andExpect(status().isBadRequest());
  jdbc.update("update container_type set active=false where id=?",plastic);
  send("/preview",input("Source","100",List.of(line(plastic,1)))).andExpect(status().isBadRequest());
  mvc.perform(get("/api/container-types").param("context","EWASTE_PICKUP").with(user(staff).roles("STAFF"))).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == "+plastic+")]").isEmpty());
 }
 @Test void snapshotsSurviveSettingsChangesInactiveReferencesAndCorrections()throws Exception {
  var body=input("Source","100",List.of(line(plastic,1)));var row=created(body);long id=row.get("id").asLong();
  jdbc.update("update container_type set name='Renamed plastic',tare_weight=9,active=false where id=?",plastic);jdbc.update("update container_type_applicability set active=false where container_type_id=? and context='EWASTE_PICKUP'",plastic);
  mvc.perform(get("/api/ewaste-pickups/"+id).with(user(staff).roles("STAFF"))).andExpect(jsonPath("$.containers[0].name").value("Plastic Pallet")).andExpect(jsonPath("$.netWeight").value("94.00"));
  body.put("version",row.get("version").asLong());body.put("containers",List.of(line(plastic,2)));
  update(id,body).andExpect(status().isOk()).andExpect(jsonPath("$.totalTareWeight").value("12.00")).andExpect(jsonPath("$.containers[0].tarePerUnit").value("6.00"));
  update(id,body).andExpect(status().isConflict());
 }
 @Test void retriesReturnOriginalSnapshotsAndConflictingIdsAreRejected()throws Exception {
  var body=input("Next Band","100.00",List.of(line(plastic,1)));var row=created(body);
  jdbc.update("update container_type set tare_weight=9 where id=?",plastic);
  send("",body).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(row.get("id").asLong())).andExpect(jsonPath("$.netWeight").value("94.00"));
  body.put("enteredWeight","101.00");send("",body).andExpect(status().isConflict());
  assertThat(jdbc.queryForObject("select count(*) from ewaste_pickup_record where submission_id=?",Integer.class,body.get("submissionId"))).isEqualTo(1);
 }
 @Test void suggestionsAreCaseInsensitiveTrimmedDistinctAndExcludeDeleted()throws Exception {
  var body=input(" Next Band ","10",List.of());var row=created(body);created(input("Next Band","11",List.of()));created(input("New source","1",List.of()));
  mvc.perform(get("/api/ewaste-pickups/organisations").param("query","nExT b").with(user(staff).roles("STAFF"))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0]").value("Next Band"));
  jdbc.update("update ewaste_pickup_record set deleted_at=now(),deleted_by=created_by where organisation='Next Band'");
  mvc.perform(get("/api/ewaste-pickups/organisations").param("query","next").with(user(staff).roles("STAFF"))).andExpect(jsonPath("$.length()").value(0));
 }
 @Test void staffCannotCorrectDeleteOrPreviewAdminChangesAndWritesRequireCsrf()throws Exception {
  var body=input("Source","10",List.of());var row=created(body);body.put("version",row.get("version").asLong());long id=row.get("id").asLong();
  mvc.perform(get("/api/ewaste-pickups")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/ewaste-pickups").with(user(staff).roles("STAFF")).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());
  mvc.perform(put("/api/ewaste-pickups/"+id).with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());
  mvc.perform(delete("/api/ewaste-pickups/"+id).with(user(staff).roles("STAFF")).with(csrf())).andExpect(status().isForbidden());
  mvc.perform(post("/api/ewaste-pickups/preview").param("recordId",String.valueOf(id)).with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());
 }
 @Test void reportsExportsDashboardAndMonthlyScopeKeepCollectionSeparate()throws Exception {
  created(input("=Next Band","100.01",List.of(line(plastic,1))));
  var query="/api/admin/reports/summary?from=2005-04-01&to=2005-04-30&workflow=EWASTE_PICKUP&location=next";
  mvc.perform(get(query).with(user(admin).roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.totals[?(@.metric == 'Net weight')].value").value(org.hamcrest.Matchers.hasItem("94.01")));
  mvc.perform(get(query.replace("/summary","/records")).with(user(admin).roles("ADMIN"))).andExpect(jsonPath("$.rows[0].organisation").value("=Next Band")).andExpect(jsonPath("$.rows[0].enteredWeight").value("100.01")).andExpect(jsonPath("$.rows[0].containerLines").value(org.hamcrest.Matchers.containsString("Plastic Pallet")));
  String csv=mvc.perform(get(query.replace("/summary","/export")+"&report=details&format=csv").with(user(admin).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(csv).contains("' =Next".replace(" ",""),"94.01","organisation","entered_weight","container_lines").doesNotContain("ELECTRICAL_CRATE_PALLET");
  byte[] xlsx=mvc.perform(get(query.replace("/summary","/export")+"&report=details&format=xlsx").with(user(admin).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
  try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))){assertThat(book.getSheet("Detailed Records")).isNotNull();for(var sheet:book)for(var r:sheet)for(var c:r)assertThat(c.getCellType()).isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);}
  mvc.perform(get("/api/admin/overview?month=2005-04").with(user(admin).roles("ADMIN"))).andExpect(jsonPath("$.activity[?(@.workflow == 'EWASTE_PICKUP')].count").value(org.hamcrest.Matchers.hasItem(1))).andExpect(jsonPath("$.topIntake.length()").value(0));
  String monthly=mvc.perform(get("/api/admin/waste-levy-summary?month=2005-04").with(user(admin).roles("ADMIN"))).andReturn().getResponse().getContentAsString();assertThat(monthly).doesNotContain("EWASTE_PICKUP");
 }
 @Test void softDeletionRetainsMetadataAndRemovesIntakeFromReports()throws Exception {
  var row=created(input("Deleted organisation","123.45",List.of()));long id=row.get("id").asLong();
  mvc.perform(delete("/api/ewaste-pickups/"+id).with(user(admin).roles("ADMIN")).with(csrf())).andExpect(status().isNoContent());
  mvc.perform(get("/api/ewaste-pickups/"+id).with(user(staff).roles("STAFF"))).andExpect(status().isNotFound());
  assertThat(jdbc.queryForObject("select u.username from ewaste_pickup_record r join app_user u on u.id=r.deleted_by where r.id=?",String.class,id)).isEqualTo(admin);
  mvc.perform(get("/api/admin/reports/records?from=2005-04-01&to=2005-04-30&workflow=EWASTE_PICKUP").with(user(admin).roles("ADMIN"))).andExpect(jsonPath("$.totalRows").value(0));
 }
}
