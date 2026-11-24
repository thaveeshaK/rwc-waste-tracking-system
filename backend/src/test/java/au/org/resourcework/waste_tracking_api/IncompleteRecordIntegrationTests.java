package au.org.resourcework.waste_tracking_api;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class IncompleteRecordIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    MockMvc mvc;
    MockHttpSession staff,admin;

    @BeforeEach void setup() throws Exception {
        try(var c=jdbc.getDataSource().getConnection()) { IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings()); }
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for(String prefix:List.of("crate_pallet","electrical_crate_pallet")) {
            jdbc.update("delete from "+prefix+"_material");jdbc.update("delete from "+prefix+"_record");
        }
        jdbc.update("update container_type set active=true,tare_weight=50,summary_group='CRATE' where name='Stillage Cage'");
        jdbc.update("update material_category set active=true");
        staff=login("STAFF");admin=login("ADMIN");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void defaultsAndValidationKeepExistingWeightAndCategoryRules(boolean electrical) throws Exception {
        String base=base(electrical);
        var input=input(electrical,"COMPLETED","100.00","0");input.remove("status");
        var saved=postRecord(base,input,201);
        assertThat(saved.path("status").asString()).isEqualTo("COMPLETED");
        for(String state:List.of("COMPLETED","INCOMPLETE")) {
            var invalid=input(electrical,state,"60","41");postRecord(base,invalid,400);
        }
        postRecord(base,input(electrical,"COMPLETED","99.99","0"),400);
        postRecord(base,input(electrical,"INCOMPLETE","100","0"),400);
        for(String invalid:List.of("-1","100.01","1.001"))postRecord(base,input(electrical,"INCOMPLETE",invalid,"0"),400);
        var zero=input(electrical,"INCOMPLETE","0","0");zero.put("grossWeight","50.00");
        var result=postRecord(base,zero,electrical?400:201);
        if(!electrical)assertThat(result.path("netWeight").asString()).isEqualTo("0.00");
        zero.put("submissionId",UUID.randomUUID().toString());zero.put("grossWeight","49.99");postRecord(base,zero,400);
        var unknown=input(electrical,"INCOMPLETE","20","0");unknown.put("status","UNKNOWN");postRecord(base,unknown,400);
        var future=input(electrical,"INCOMPLETE","20","0");future.put("recordDate",LocalDate.now(clock).plusDays(1).toString());postRecord(base,future,400);
        var wrong=input(electrical,"INCOMPLETE","20","0");((ObjectNode)wrong.path("materials").get(0)).put("materialCategoryId",Long.MAX_VALUE);postRecord(base,wrong,400);
        var empty=input(electrical,"INCOMPLETE","0","0");empty.putArray("materials");postRecord(base,empty,electrical?400:201);
        if(electrical) {
            var wrongCategory=input(true,"INCOMPLETE","20","0");
            ((ObjectNode)wrongCategory.path("materials").get(0)).put("materialCategoryId",jdbc.queryForObject("select id from material_category where name='Timber'",Long.class));
            postRecord(base,wrongCategory,400);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void completionPreservesIdentityAuditVersionSnapshotsAndLocksCompletedStaffAccess(boolean electrical) throws Exception {
        String base=base(electrical),table=electrical?"electrical_crate_pallet_record":"crate_pallet_record";
        var input=input(electrical,"INCOMPLETE","22.40","0");
        var saved=postRecord(base,input,201);long id=saved.path("id").asLong();
        assertThat(postRecord(base,input,200).path("id").asLong()).isEqualTo(id);
        var conflicting=input.deepCopy();conflicting.put("status","COMPLETED");postRecord(base,conflicting,409);
        var completed=completion(input,saved);
        // A later Settings edit must not change the original same-container snapshots.
        jdbc.update("update container_type set tare_weight=65,summary_group='OTHER' where id=?",input.path("containerTypeId").asLong());
        var attempted=completed.deepCopy();attempted.put("grossWeight","190");update(base,id,attempted,staff,403);
        attempted=completed.deepCopy();attempted.put("recordDate",LocalDate.now(clock).minusDays(1).toString());update(base,id,attempted,staff,403);
        var invalid=completed.deepCopy();invalid.put("version",99);update(base,id,invalid,staff,409);
        mvc.perform(post(base+"/preview").param("recordId",String.valueOf(id)).session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(completed)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tareWeight").value("50.00"));
        assertThat(getRecord(base,id).path("status").asString()).isEqualTo("INCOMPLETE");
        var updated=update(base,id,completed,staff,200);
        assertThat(updated.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(updated.path("version").asLong()).isEqualTo(saved.path("version").asLong()+1);
        assertThat(updated.path("createdBy")).isEqualTo(saved.path("createdBy"));
        assertThat(updated.path("createdAt")).isEqualTo(saved.path("createdAt"));
        assertThat(updated.path("submissionId")).isEqualTo(saved.path("submissionId"));
        assertThat(updated.path("updatedBy").asString()).isEqualTo(System.getenv("INITIAL_STAFF_USERNAME"));
        assertThat(Instant.parse(updated.path("updatedAt").asString())).isAfterOrEqualTo(Instant.parse(saved.path("updatedAt").asString()));
        assertThat(updated.path("tareWeight").asString()).isEqualTo("50.00");
        assertThat(updated.path("netWeight").asString()).isEqualTo("100.00");
        assertThat(jdbc.queryForObject("select summary_group_snapshot from "+table+" where id=?",String.class,id)).isEqualTo("CRATE");
        completed.put("version",updated.path("version").asLong());
        update(base,id,completed,staff,403); // Even an identical repeat may not edit a completed record.
        completed.put("status","INCOMPLETE");update(base,id,completed,staff,403);
        mvc.perform(post(base+"/preview").param("recordId",String.valueOf(id)).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(completed))).andExpect(status().isForbidden());
        completed.put("status","COMPLETED");completed.put("grossWeight","160.00");
        var corrected=update(base,id,completed,admin,200);
        assertThat(corrected.path("updatedBy").asString()).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        assertThat(corrected.path("netWeight").asString()).isEqualTo("110.00");
        mvc.perform(delete(base+"/"+id).session(staff).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete(base+"/"+id).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(base+"/"+id).session(staff)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from "+table+" where id=? and deleted_at is not null",Integer.class,id)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void incompleteIsVisibleInRecordsButExcludedFromEveryReportingSurfaceUntilCompleted(boolean electrical) throws Exception {
        String base=base(electrical),workflow=electrical?"ELECTRICAL_CRATE_PALLET":"CRATE_PALLET";
        String date=LocalDate.now(clock).toString(),month=YearMonth.from(LocalDate.now(clock)).toString();
        String query="?from="+date+"&to="+date+"&workflow="+workflow;
        List<String> paths=List.of("/api/admin/overview","/api/operations/today-activity","/api/operations/recent-submissions",
                "/api/admin/reports/summary"+query,"/api/admin/reports/records"+query,"/api/admin/waste-levy-summary?month="+month);
        var before=new HashMap<String,JsonNode>();for(String path:paths)before.put(path,stable(getJson(path,admin)));
        var input=input(electrical,"INCOMPLETE","22.40","0");var saved=postRecord(base,input,201);long id=saved.path("id").asLong();
        for(String path:paths)assertThat(stable(getJson(path,admin))).as(path).isEqualTo(before.get(path));
        mvc.perform(get("/api/records").param("workflow",workflow).session(staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].record.status").value("INCOMPLETE"));
        mvc.perform(get(base).session(staff)).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("INCOMPLETE"));
        mvc.perform(get(base+"/"+id).session(staff)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INCOMPLETE"));
        for(String format:List.of("csv","xlsx")) {
            var bytes=mvc.perform(get("/api/admin/reports/export"+query+"&format="+format+"&report=details").session(admin))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(exportText(bytes,format)).doesNotContain(input.path("submissionId").asString());
        }
        update(base,id,completion(input,saved),staff,200);
        var overview=getJson("/api/admin/overview",admin);
        assertThat(overview.path("recordsThisMonth").asLong()).isEqualTo(before.get("/api/admin/overview").path("recordsThisMonth").asLong()+1);
        assertThat(overview.path("submittedToday").asLong()).isEqualTo(before.get("/api/admin/overview").path("submittedToday").asLong()+1);
        var day=getJson("/api/operations/today-activity",staff).path("todayActivity");
        var card=java.util.stream.StreamSupport.stream(day.spliterator(),false).filter(v->v.path("workflow").asString().equals(workflow)).findFirst().orElseThrow();
        assertThat(card.path("metrics").get(0).path("value").asString()).isEqualTo("1");
        assertThat(card.path("metrics").get(2).path("value").asString()).isEqualTo("100.00");
        var details=getJson("/api/admin/reports/records"+query,admin);
        assertThat(details.path("totalRows").asLong()).isGreaterThan(0);
        assertThat(getJson("/api/admin/reports/summary"+query,admin).path("totalItems").asLong()).isGreaterThan(0);
        assertThat(getJson("/api/admin/waste-levy-summary?month="+month,admin).toString()).contains("100.00");
        for(String format:List.of("csv","xlsx")) {
            var bytes=mvc.perform(get("/api/admin/reports/export"+query+"&format="+format+"&report=details").session(admin))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(exportText(bytes,format)).contains(input.path("submissionId").asString());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void completionEndpointsRetainAuthenticationCsrfAndVersionRequirements(boolean electrical) throws Exception {
        String base=base(electrical);var input=input(electrical,"INCOMPLETE","0","0");var saved=postRecord(base,input,201);
        var completed=completion(input,saved);long id=saved.path("id").asLong();
        mvc.perform(put(base+"/"+id).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(completed))).andExpect(status().isUnauthorized());
        mvc.perform(put(base+"/"+id).session(staff).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(completed))).andExpect(status().isForbidden());
        completed.remove("version");update(base,id,completed,staff,400);
        completed.put("version",saved.path("version").asLong());
        ((ObjectNode)completed.path("materials").get(0)).put("percentage","80.00");update(base,id,completed,staff,400);
        assertThat(getRecord(base,id).path("status").asString()).isEqualTo("INCOMPLETE");
    }

    private String exportText(byte[] bytes,String format) throws Exception {
        if(format.equals("csv"))return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            var text=new StringBuilder();for(var sheet:book)for(var row:sheet)for(var cell:row)text.append(cell.toString()).append('|');return text.toString();
        }
    }
    private JsonNode stable(JsonNode value) {
        if(value.isObject())((ObjectNode)value).remove("generatedAt");
        for(var child:value)stable(child);return value;
    }
    private String base(boolean electrical) {return electrical?"/api/electrical-crate-pallet-records":"/api/crate-pallet-records";}
    private ObjectNode input(boolean electrical,String state,String a,String b) {
        var input=json.createObjectNode();input.put("submissionId",UUID.randomUUID().toString());input.put("recordDate",LocalDate.now(clock).toString());
        input.put("containerTypeId",jdbc.queryForObject("select id from container_type where name='Stillage Cage'",Long.class));input.put("grossWeight","150.00");input.put("status",state);
        var ids=jdbc.queryForList("select c.id from material_category c join material_category_applicability a on a.material_category_id=c.id where a.context=? order by c.id limit 2",Long.class,electrical?"ELECTRICAL_CRATE_PALLET":"CRATE_PALLET");
        var materials=input.putArray("materials");for(int i=0;i<2;i++)materials.addObject().put("materialCategoryId",ids.get(i)).put("percentage",i==0?a:b);return input;
    }
    private ObjectNode completion(ObjectNode input,JsonNode saved) {
        var result=input.deepCopy();result.put("status","COMPLETED");result.put("version",saved.path("version").asLong());
        ((ObjectNode)result.path("materials").get(0)).put("percentage","22.40");((ObjectNode)result.path("materials").get(1)).put("percentage","77.60");return result;
    }
    private JsonNode postRecord(String base,ObjectNode input,int expected) throws Exception {
        return json.readTree(mvc.perform(post(base).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private JsonNode update(String base,long id,ObjectNode input,MockHttpSession session,int expected) throws Exception {
        return json.readTree(mvc.perform(put(base+"/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getRecord(String base,long id) throws Exception {return getJson(base+"/"+id,staff);}
    private JsonNode getJson(String path,MockHttpSession session) throws Exception {
        return json.readTree(mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private MockHttpSession login(String role) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",System.getenv("INITIAL_"+role+"_USERNAME"))
                .param("password",System.getenv("INITIAL_"+role+"_PASSWORD"))).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
}
