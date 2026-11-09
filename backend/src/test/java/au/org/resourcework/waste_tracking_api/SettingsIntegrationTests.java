package au.org.resourcework.waste_tracking_api;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"debug=false","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO"})
@Transactional
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class SettingsIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired jakarta.persistence.EntityManager entities;
    MockMvc mvc;
    String admin;
    long iron;
    static final String BASE="/api/admin/settings";
    @BeforeEach void setup() throws Exception {
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings());}
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin=jdbc.queryForObject("select username from app_user where role='ADMIN' limit 1",String.class);
        iron=jdbc.queryForObject("select id from material_category where name='Iron and Steel'",Long.class);
    }
    private JsonNode read(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).with(user(admin).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode write(String path,String method,Object body,int expected) throws Exception {
        var response=mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),path).with(user(admin).roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(expected)).andReturn().getResponse();
        entities.flush();
        entities.clear(); // Separate HTTP requests do not share managed reference entities in production.
        return response.getContentAsString().isEmpty()?null:json.readTree(response.getContentAsString());
    }
    private ObjectNode configuration() throws Exception {return (ObjectNode)read(BASE);}
    private Map<String,Object> reference(String name,String tare,boolean active,String... contexts) {
        var value=new HashMap<String,Object>();value.put("name",name);value.put("tareWeight",tare);value.put("active",active);value.put("contexts",List.of(contexts));if(tare!=null)value.put("summaryGroup","OTHER");return value;
    }
    private Map<String,Object> crate(long container,long material,String gross,boolean electrical) throws Exception {
        var materials=new ArrayList<Map<String,Object>>();
        if(electrical) for(var c:read("/api/material-categories?context=ELECTRICAL_CRATE_PALLET")) materials.add(Map.of("materialCategoryId",c.path("id").asLong(),"percentage",materials.isEmpty()?"100":"0"));
        else materials.add(Map.of("materialCategoryId",material,"percentage","100"));
        return new HashMap<>(Map.of("submissionId",UUID.randomUUID(),"recordDate","2006-01-01","containerTypeId",container,"grossWeight",gross,"materials",materials));
    }
    @Test void allSettingsEndpointsRequireAdminAndCsrf() throws Exception {
        for(String path:List.of(BASE,BASE+"/container-types",BASE+"/material-categories")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("staff").roles("STAFF"))).andExpect(status().isForbidden());
            mvc.perform(put(path+"/1").with(user("staff").roles("STAFF")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        }
        mvc.perform(put(BASE).with(user(admin).roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/container-types").with(user("staff").roles("STAFF")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        mvc.perform(get(BASE).with(user(admin).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/operational-configuration").with(user("staff").roles("STAFF"))).andExpect(status().isOk()).andExpect(jsonPath("$.timezone").value("Australia/Hobart")).andExpect(jsonPath("$.displayUnit").doesNotExist());
    }
    @Test void configurationDefaultsAndUpdatesAreExplicitVersionedAndAudited() throws Exception {
        var c=configuration();
        assertThat(c.path("whitegoodsKgPerItem").asText()).isEqualTo("48.00");assertThat(c.path("financialYearStartMonth").asInt()).isEqualTo(7);
        var changed=c.deepCopy().put("organisationName","New Organisation").put("siteName","Depot").put("reportDisplayName","Report Header").put("displayUnit","tonnes").put("reportPeriod","CURRENT_FINANCIAL_YEAR").put("financialYearStartMonth",4);
        var saved=write(BASE,"PUT",changed,200);assertThat(saved.path("updatedBy").asText()).isEqualTo(admin);assertThat(saved.path("version").asLong()).isEqualTo(c.path("version").asLong()+1);
        write(BASE,"PUT",changed,409);
        assertThat(read("/api/operational-configuration").path("organisationName").asText()).isEqualTo("New Organisation");
    }
    @Test void invalidSettingsAreRejectedBeforeStorage() throws Exception {
        for(String factor:List.of("0","-1","0.001","1000","invalid")) write(BASE,"PUT",configuration().put("whitegoodsKgPerItem",factor),400);
        write(BASE,"PUT",configuration().put("organisationName"," "),400);
        write(BASE,"PUT",configuration().put("reportDisplayName"," "),400);
        write(BASE,"PUT",configuration().put("financialYearStartMonth",13),400);
        write(BASE,"PUT",configuration().put("displayUnit","lb"),400);
        write(BASE,"PUT",configuration().put("reportPeriod","WEEK"),400);
    }
    @Test void containerValidationDuplicateHandlingAndApplicability() throws Exception {
        write(BASE+"/container-types","POST",reference("Invalid","-1",true,"CRATE_PALLET"),400);
        write(BASE+"/container-types","POST",reference("Invalid","1.001",true,"CRATE_PALLET"),400);
        write(BASE+"/container-types","POST",reference("Invalid","1",true),400);
        write(BASE+"/container-types","POST",reference("Invalid","1",true,"PICKUP"),400);
        var created=write(BASE+"/container-types","POST",reference("Settings crate","0",true,"CRATE_PALLET"),200);
        long id=created.path("id").asLong();
        var edit=reference("Settings crate","3.50",true,"ELECTRICAL_CRATE_PALLET");edit.put("version",created.path("version").asLong());
        var saved=write(BASE+"/container-types/"+id,"PUT",edit,200);
        write(BASE+"/container-types/"+id,"PUT",edit,409);
        assertThat(read("/api/container-types?context=CRATE_PALLET").toString()).doesNotContain("Settings crate");
        assertThat(read("/api/container-types?context=ELECTRICAL_CRATE_PALLET").toString()).contains("Settings crate");
        var inactive=reference("Settings crate","3.50",false);inactive.put("version",saved.path("version").asLong());write(BASE+"/container-types/"+id,"PUT",inactive,200);
        assertThat(jdbc.queryForObject("select count(*) from container_type_applicability where container_type_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select settings_updated_by is not null from container_type where id=?",Boolean.class,id)).isTrue();
        // Unique indexes also protect concurrent/case-insensitive creates. Last SQL in this rollback test.
        write(BASE+"/container-types","POST",reference(" settings CRATE ","1",true,"CRATE_PALLET"),409);
    }
    @Test void tareAndDisplayNameUpdatesLeaveHistoricalRecordsAndReportsUnchanged() throws Exception {
        for(boolean electrical:List.of(false,true)) {
            String context=electrical?"ELECTRICAL_CRATE_PALLET":"CRATE_PALLET";
            String base=electrical?"/api/electrical-crate-pallet-records":"/api/crate-pallet-records";
            String original="History "+context;
            var container=write(BASE+"/container-types","POST",reference(original,"2",true,context),200);long id=container.path("id").asLong();
            var input=crate(id,iron,"12",electrical);var record=write(base,"POST",input,201);
            var edit=reference("Renamed "+context,"9",false);edit.put("version",container.path("version").asLong());write(BASE+"/container-types/"+id,"PUT",edit,200);
            var history=read(base+"/"+record.path("id").asLong());assertThat(history.path("tareWeight").asText()).isEqualTo("2.00");assertThat(history.path("containerTypeName").asText()).isEqualTo(original);assertThat(history.path("updatedAt")).isEqualTo(record.path("updatedAt"));assertThat(history.path("version")).isEqualTo(record.path("version"));
            assertThat(read("/api/admin/reports/summary?from=2006-01-01&to=2006-01-01&workflow="+context).toString()).contains(original).doesNotContain("Renamed");
            write(base,"POST",crate(id,iron,"12",electrical),400);
            input.put("version",history.path("version").asLong());var preview=write(base+"/preview?recordId="+record.path("id").asLong(),"POST",input,200);assertThat(preview.path("tareWeight").asText()).isEqualTo("2.00");
            var current=read(BASE+"/container-types");JsonNode row=null;for(var c:current) if(c.path("id").asLong()==id) row=c;
            edit=reference("Renamed "+context,"9",true,context);edit.put("version",row.path("version").asLong());write(BASE+"/container-types/"+id,"PUT",edit,200);
            var future=write(base,"POST",crate(id,iron,"12",electrical),201);assertThat(future.path("tareWeight").asText()).isEqualTo("9.00");assertThat(future.path("netWeight").asText()).isEqualTo("3.00");
        }
    }
    @Test void containerSummaryGroupsAreExplicitAndUnrelatedWorkflowsRemainOptional() throws Exception {
        for(String group:List.of("CRATE","PALLET","OTHER")) {
            var input=reference("Group "+group,"1",true,"CRATE_PALLET");input.put("summaryGroup",group);
            assertThat(write(BASE+"/container-types","POST",input,200).path("summaryGroup").asText()).isEqualTo(group);
        }
        var missing=reference("Missing group","1",true,"CRATE_PALLET");missing.remove("summaryGroup");
        write(BASE+"/container-types","POST",missing,400);
        missing.put("summaryGroup","GUESS");write(BASE+"/container-types","POST",missing,400);
        missing.remove("summaryGroup");missing.put("contexts",List.of("EWASTE_PICKUP"));
        write(BASE+"/container-types","POST",missing,200);
    }
    @Test void summaryGroupSnapshotsSurviveSettingsChangesRetriesAndSameContainerCorrections() throws Exception {
        var input=reference("Custom name unrelated to grouping","2",true,"CRATE_PALLET");input.put("summaryGroup","CRATE");
        var container=write(BASE+"/container-types","POST",input,200);long id=container.path("id").asLong();
        var payload=crate(id,iron,"12",false);var first=write("/api/crate-pallet-records","POST",payload,201);
        input.put("version",container.path("version").asLong());input.put("summaryGroup","PALLET");
        write(BASE+"/container-types/"+id,"PUT",input,200);
        write("/api/crate-pallet-records","POST",payload,200); // retry retains its original group
        payload.put("version",first.path("version").asLong());payload.put("grossWeight","13");
        write("/api/crate-pallet-records/"+first.path("id").asLong(),"PUT",payload,200);
        assertThat(jdbc.queryForObject("select summary_group_snapshot from crate_pallet_record where id=?",String.class,first.path("id").asLong())).isEqualTo("CRATE");
        var second=write("/api/crate-pallet-records","POST",crate(id,iron,"12",false),201);
        assertThat(jdbc.queryForObject("select summary_group_snapshot from crate_pallet_record where id=?",String.class,second.path("id").asLong())).isEqualTo("PALLET");
        var monthly=read("/api/admin/waste-levy-summary?month=2006-01").path("containerMaterials");
        assertThat(monthly.toString()).contains("CRATE","PALLET","11.00","10.00");
        write("/api/crate-pallet-records/"+second.path("id").asLong(),"DELETE",Map.of(),204);
        assertThat(read("/api/admin/waste-levy-summary?month=2006-01").path("containerMaterials").toString()).doesNotContain("PALLET");
    }
    @Test void materialIdentityAndInactiveHistoricalReferencesArePreserved() throws Exception {
        var category=write(BASE+"/material-categories","POST",reference("Settings material",null,true,"CRATE_PALLET"),200);long id=category.path("id").asLong();
        long container=jdbc.queryForObject("select id from container_type where name='Plastic Pallet'",Long.class);
        var record=write("/api/crate-pallet-records","POST",crate(container,id,"16",false),201);
        var invalid=reference("Renamed material",null,true,"CRATE_PALLET");invalid.put("version",0);write(BASE+"/material-categories/"+id,"PUT",invalid,400);
        var inactive=reference("Settings material",null,false);inactive.put("version",0);write(BASE+"/material-categories/"+id,"PUT",inactive,200);
        assertThat(read("/api/material-categories?context=CRATE_PALLET").toString()).doesNotContain("Settings material");
        assertThat(read("/api/crate-pallet-records/"+record.path("id").asLong()).toString()).contains("Settings material");
        assertThat(read("/api/admin/reports/summary?from=2006-01-01&to=2006-01-01").toString()).contains("Settings material");
        write("/api/crate-pallet-records","POST",crate(container,id,"16",false),400);
        var enabled=reference("Settings material",null,true,"PICKUP","COLLECTABLES");enabled.put("version",1);write(BASE+"/material-categories/"+id,"PUT",enabled,200);
        assertThat(read("/api/material-categories?context=PICKUP").toString()).contains("Settings material");
        write(BASE+"/material-categories","POST",reference("bad electrical",null,true,"ELECTRICAL_CRATE_PALLET"),400);
        long fixed=jdbc.queryForObject("select id from material_category where name='Everything Else'",Long.class);
        write(BASE+"/material-categories/"+fixed,"PUT",reference("Everything Else",null,false),400);
        assertThat(jdbc.queryForObject("select count(*) from electrical_testing_category",Integer.class)).isEqualTo(15);
    }
    @Test void factorChangesAffectFutureEntriesAndMonthlyWeightsSumHistoricalEstimates() throws Exception {
        var input=new HashMap<String,Object>(Map.of("submissionId",UUID.randomUUID(),"recordDate","2006-01-01","count",2));
        var old=write("/api/whitegoods-records","POST",input,201);
        write(BASE,"PUT",configuration().put("whitegoodsKgPerItem","52.25"),200);
        var retry=write("/api/whitegoods-records","POST",input,200);assertThat(retry.path("kgPerItem").asText()).isEqualTo("48.00");
        input.put("submissionId",UUID.randomUUID());var future=write("/api/whitegoods-records","POST",input,201);assertThat(future.path("estimatedWeight").asText()).isEqualTo("104.50");
        var month=read("/api/whitegoods-records/monthly-summary?month=2006-01");assertThat(month.path("totalCount").asLong()).isEqualTo(4);assertThat(month.path("estimatedWeight").asText()).isEqualTo("200.50");assertThat(month.path("kgPerItem").isNull()).isTrue();
        input.put("submissionId",old.path("submissionId").asText());input.put("count",3);input.put("version",old.path("version").asLong());
        var correction=write("/api/whitegoods-records/"+old.path("id").asLong(),"PUT",input,200);assertThat(correction.path("kgPerItem").asText()).isEqualTo("48.00");assertThat(correction.path("estimatedWeight").asText()).isEqualTo("144.00");
    }
    @Test void maximumFactorAndCountFitWithoutLossOfPrecision() throws Exception {
        write(BASE,"PUT",configuration().put("whitegoodsKgPerItem","999.99"),200);
        var record=write("/api/whitegoods-records","POST",Map.of("submissionId",UUID.randomUUID(),"recordDate","2006-01-01","count",2147483647),201);
        assertThat(record.path("estimatedWeight").asText()).isEqualTo("2147462172163.53");
    }
}
