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
class ScrapIntegrationTests {
 @Autowired WebApplicationContext context;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired Clock clock;
 MockMvc mvc; String staff,admin;
 @BeforeEach void setup() throws Exception {
  try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings());}
  mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  staff=jdbc.queryForObject("select username from app_user where role='STAFF' limit 1",String.class);
  admin=jdbc.queryForObject("select username from app_user where role='ADMIN' limit 1",String.class);
 }
 Map<String,Object> input(String month,String iron,String aluminium,String other,String mixed,String ewaste) {
  var m=new HashMap<String,Object>();m.put("submissionId",UUID.randomUUID());m.put("reportingMonth",month);
  String[] keys={"ironAndSteelWeight","aluminiumWeight","otherNonFerrousWeight","mixedMetalsWeight","ewasteWeight"};
  String[] values={iron,aluminium,other,mixed,ewaste};for(int i=0;i<5;i++)m.put(keys[i],values[i]);return m;
 }
 Map<String,Object> entry(String month,String weight){return input(month,weight,"0","0","0","0");}
 ResultActions send(String suffix,Map<String,Object> body)throws Exception{return mvc.perform(post("/api/scrap-records"+suffix).with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)));}
 JsonNode created(Map<String,Object> body)throws Exception{return json.readTree(send("",body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
 ResultActions get(String path)throws Exception{return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(user(staff).roles("STAFF")));}
 ResultActions update(long id,Map<String,Object> body)throws Exception{return mvc.perform(put("/api/scrap-records/"+id).with(user(admin).roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)));}
 ResultActions remove(long id)throws Exception{return mvc.perform(delete("/api/scrap-records/"+id).with(user(admin).roles("ADMIN")).with(csrf()));}
 @Test void oneMaterialAndZeroValuesAreSavedExactly()throws Exception {
  var row=created(entry("2004-09","100.25"));assertThat(row.get("ironAndSteelWeight").asText()).isEqualTo("100.25");assertThat(row.get("ewasteWeight").asText()).isEqualTo("0.00");assertThat(row.get("totalWeight").asText()).isEqualTo("100.25");assertThat(row.get("createdBy").asText()).isEqualTo(staff);
 }
 @Test void invalidInputsAreRejected()throws Exception {
  for(String weight:List.of("0","-0.01","0.001","100000000.00"))send("/preview",entry("2004-09",weight)).andExpect(status().isBadRequest());
  for(String month:List.of("2004-13","2004-09-01","0000-01","9999-01",""))send("/preview",entry(month,"1")).andExpect(status().isBadRequest());
  var missing=entry("2004-09","1");missing.remove("ewasteWeight");send("",missing).andExpect(status().isBadRequest());
 }
 @Test void additiveMonthsAndDecimalTotalsAreIndependent()throws Exception {
  created(entry("2004-09","0.10"));created(input("2004-09","0.20","2.25","3.33","4.44","5.55"));created(entry("2004-10","999"));
  get("/api/scrap-records/monthly-summary?month=2004-09").andExpect(jsonPath("$.ironAndSteelWeight").value("0.30")).andExpect(jsonPath("$.totalWeight").value("15.87"));
  get("/api/scrap-records/monthly-summary?month=2004-08").andExpect(jsonPath("$.totalWeight").value("0.00"));
 }
 @Test void reviewShowsCurrentEntryAndProjectedBreakdownWithoutSaving()throws Exception {
  created(input("2004-09","250","0","75","10","477"));var pending=input("2004-09","100","0","25","0","100");
  send("/preview",pending).andExpect(status().isOk()).andExpect(jsonPath("$.current.totalWeight").value("812.00")).andExpect(jsonPath("$.entry.totalWeight").value("225.00")).andExpect(jsonPath("$.projected.totalWeight").value("1037.00")).andExpect(jsonPath("$.projected.ironAndSteelWeight").value("350.00")).andExpect(jsonPath("$.projected.otherNonFerrousWeight").value("100.00")).andExpect(jsonPath("$.projected.ewasteWeight").value("577.00"));
  assertThat(jdbc.queryForObject("select count(*) from scrap_record where submission_id=?",Integer.class,pending.get("submissionId"))).isZero();
 }
 @Test void correctionPreviewExcludesOriginalAndMonthMoveUpdatesBothMonths()throws Exception {
  created(entry("2004-09","10"));var body=entry("2004-09","20");var row=created(body);long id=row.get("id").asLong();body.put("version",row.get("version").asLong());body.put("ironAndSteelWeight","30");
  mvc.perform(post("/api/scrap-records/preview?recordId="+id).with(user(admin).roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(jsonPath("$.current.totalWeight").value("10.00")).andExpect(jsonPath("$.projected.totalWeight").value("40.00"));
  created(entry("2004-10","5"));body.put("reportingMonth","2004-10");
  mvc.perform(post("/api/scrap-records/preview?recordId="+id).with(user(admin).roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(jsonPath("$.current.totalWeight").value("5.00")).andExpect(jsonPath("$.projected.totalWeight").value("35.00"));
  update(id,body).andExpect(status().isOk());update(id,body).andExpect(status().isConflict());
  get("/api/scrap-records/monthly-summary?month=2004-09").andExpect(jsonPath("$.totalWeight").value("10.00"));get("/api/scrap-records/monthly-summary?month=2004-10").andExpect(jsonPath("$.totalWeight").value("35.00"));
 }
 @Test void softDeletedRowsDoNotContributeToPreviewOrSummary()throws Exception {
  var row=created(entry("2004-09","20"));long id=row.get("id").asLong();remove(id).andExpect(status().isNoContent());get("/api/scrap-records/"+id).andExpect(status().isNotFound());
  send("/preview",entry("2004-09","1")).andExpect(jsonPath("$.current.totalWeight").value("0.00"));get("/api/scrap-records/monthly-summary?month=2004-09").andExpect(jsonPath("$.totalWeight").value("0.00"));
  assertThat(jdbc.queryForObject("select u.username from scrap_record r join app_user u on u.id=r.deleted_by where r.id=?",String.class,id)).isEqualTo(admin);
 }
 @Test void retryIsIdempotentAndChangedPayloadConflicts()throws Exception {
  var body=entry("2004-09","1.10");var row=created(body);send("",body).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(row.get("id").asLong()));body.put("ewasteWeight","1");send("",body).andExpect(status().isConflict());
  assertThat(jdbc.queryForObject("select count(*) from scrap_record where submission_id=?",Integer.class,body.get("submissionId"))).isEqualTo(1);
 }
 @Test void roleAndCsrfRulesMatchOtherWorkflows()throws Exception {
  var body=entry("2004-09","1");var row=created(body);long id=row.get("id").asLong();body.put("version",row.get("version").asLong());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/scrap-records")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/scrap-records").with(user(staff).roles("STAFF")).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());
  mvc.perform(put("/api/scrap-records/"+id).with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());
  mvc.perform(delete("/api/scrap-records/"+id).with(user(staff).roles("STAFF")).with(csrf())).andExpect(status().isForbidden());
  send("/preview?recordId="+id,body).andExpect(status().isForbidden());
 }
 @Test void reportsUseReportingMonthAndKeepScrapIndependent()throws Exception {
  created(input("2004-09","100","0","25","0","100"));created(entry("2004-10","999"));
  var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/reports/summary?from=2004-09-20&to=2004-09-21&workflow=SCRAP").with(user(admin).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
  assertThat(response.getContentAsString()).contains("Other Non-Ferrous","225.00","E-waste").doesNotContain("EWASTE_PICKUP","ELECTRICAL_CRATE_PALLET");
  String monthly=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/waste-levy-summary?month=2004-09").with(user(admin).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(monthly).contains("SCRAP","225.00");
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/overview?month=2004-09").with(user(admin).roles("ADMIN"))).andExpect(jsonPath("$.activity[?(@.workflow == 'SCRAP')].count").value(org.hamcrest.Matchers.hasItem(1))).andExpect(jsonPath("$.topIntake.length()").value(0));
 }
 @Test void exportsContainFiveFieldsAndExcludeDeletedSubmissions()throws Exception {
  var row=created(input("2004-09","100.25","2","3","4","5"));String url="/api/admin/reports/export?from=2004-09-15&to=2004-09-16&workflow=SCRAP&report=details&format=";
  String csv=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url+"csv").with(user(admin).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(csv).contains("2004-09","Iron and Steel","Other Non-Ferrous","E-waste","114.25");
  byte[] data=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url+"xlsx").with(user(admin).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(data))){int rows=0;for(var r:book.getSheet("Material Breakdown"))if(r.getCell(0)!=null && r.getCell(0).toString().equals("MATERIAL"))rows++;assertThat(rows).isEqualTo(5);}
  remove(row.get("id").asLong());get("/api/scrap-records/"+row.get("id").asLong()).andExpect(status().isNotFound());mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/reports/records?from=2004-09-01&to=2004-09-30&workflow=SCRAP").with(user(admin).roles("ADMIN"))).andExpect(jsonPath("$.totalRows").value(0));
 }
}
