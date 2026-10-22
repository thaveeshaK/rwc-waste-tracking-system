package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.electricaltesting.*;
import au.org.resourcework.waste_tracking_api.electricaltesting.dto.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"debug=false", "logging.level.org.springframework=INFO", "logging.level.org.hibernate=INFO"})
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@ContextConfiguration(initializers = IsolatedTestDatabase.class)
class ElectricalTestingIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired ElectricalTestingTallyService service;
    @Autowired ElectricalTestingSalesReferenceService references;
    long category;
    MockMvc mvc;
    MockHttpSession staff;
    MockHttpSession admin;
    private boolean verified;

    @BeforeEach
    void setup() throws Exception {
        verified = false;
        verifyIsolation();
        verified = true;
        jdbc.update("DELETE FROM electrical_testing_tally");
        jdbc.update("DELETE FROM electrical_testing_sales_reference");
        category=jdbc.queryForObject("SELECT id FROM electrical_testing_category WHERE name='Kitchen/household'",Long.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        staff = login("STAFF");
        admin = login("ADMIN");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!verified) return;
        verifyIsolation();
        jdbc.update("UPDATE electrical_testing_category SET active=true");
    }

    private void verifyIsolation() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            IsolatedTestDatabase.verify(connection, IsolatedTestDatabase.settings());
        }
    }

    private static final String BASE="/api/electrical-testing";
    @Test void categoriesAreDedicatedAndMatchAllFifteenApprovedNames() throws Exception {
        var names=jdbc.queryForList("SELECT name FROM electrical_testing_category ORDER BY display_order",String.class);
        assertThat(names).containsExactly("Kitchen/household","Heaters","Fans/aircon","Lamps","Lighting","iPod docks/clock radios","Stereos and speakers","Surround sound/subwoofers","DVD/VHS/games consoles","TVs/monitors/projectors","Sewing machines","Power tools","Vacuums","Whitegoods","Vintage radios/parts");
        mvc.perform(get(BASE+"/categories").session(staff)).andExpect(jsonPath("$.length()").value(15));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_category",Integer.class)).isEqualTo(18);
    }
    @Test void positiveCategoryAndOverallDiversionTalliesAggregateIndependently() throws Exception {
        var input=tally("2",TallyType.TESTED_AND_PRICED,category);
        mvc.perform(post(BASE+"/tallies/preview").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isOk());
        assertThat(count()).isZero();
        createTally(input); createTally(tally("3",TallyType.TESTED_AND_PRICED,category));
        createTally(tally("4",TallyType.UNTESTED_BROKEN_FAILED,category)); createTally(tally("1",TallyType.STOCK_CULL,category));
        createTally(tally("6",TallyType.DIVERTED_TO_OLS,null)); createTally(tally("7",TallyType.DIVERTED_TO_COLL,null));
        var summary=summary();
        assertThat(summary.path("categories").get(0).path("testedAndPriced").asLong()).isEqualTo(5);
        assertThat(summary.path("categories").get(0).path("untestedBrokenFailed").asLong()).isEqualTo(4);
        assertThat(summary.path("categories").get(0).path("stockCull").asLong()).isEqualTo(1);
        assertThat(summary.path("divertedToOls").asLong()).isEqualTo(6); assertThat(summary.path("divertedToColl").asLong()).isEqualTo(7);
    }
    @Test void talliesRejectZeroFractionsBadCategoryCombinationsAndFutureDates() throws Exception {
        for(var type:TallyType.values()) {
            for(String n:List.of("0","-1","1.5","2147483648")) {
                postStatus("/tallies",tally(n,type,type.requiresCategory()?category:null),staff,400);
            }
            postStatus("/tallies",tally("1",type,type.requiresCategory()?null:category),staff,400);
        }
        var future=new TallyRequest(UUID.randomUUID(),LocalDate.now(clock).plusDays(1),category,TallyType.STOCK_CULL,BigDecimal.ONE,null);
        postStatus("/tallies",future,staff,400);
        postStatus("/tallies",tally("1",TallyType.TESTED_AND_PRICED,999999L),staff,400);
        assertThat(count()).isZero();
    }
    @Test void salesReferencesDistinguishMissingAndZeroEnforceUniqueActiveMonthAndDoNotAffectTallies() throws Exception {
        String month=YearMonth.now(clock).toString();
        mvc.perform(get(BASE+"/sales-references").session(staff).param("month",month)).andExpect(jsonPath("$.length()").value(0));
        var input=reference("0"); var saved=createReference(input);
        assertThat(saved.path("soldLastMonthCount").asInt()).isZero();
        postStatus("/sales-references",input,staff,200);
        postStatus("/sales-references",reference("1"),staff,409);
        postStatus("/sales-references",reference("-1"),staff,400); postStatus("/sales-references",reference("0.5"),staff,400);
        assertThat(summary().path("categories").get(0).path("testedAndPriced").asLong()).isZero();
        mvc.perform(delete(BASE+"/sales-references/"+saved.path("id").asLong()).session(admin).with(csrf())).andExpect(status().isNoContent());
        createReference(reference("2")); // Soft-deleted reference no longer occupies the category/month.
        postStatus("/sales-references",input,staff,409);
    }
    @Test void tallyCorrectionsMoveMonthlyTotalsAndDeletionExcludesCounts() throws Exception {
        var input=tally("2",TallyType.TESTED_AND_PRICED,category); var saved=createTally(input); long id=saved.path("id").asLong();
        var corrected=new TallyRequest(input.submissionId(),LocalDate.of(2024,2,29),null,TallyType.DIVERTED_TO_OLS,new BigDecimal("5"),saved.path("version").asLong());
        var updated=putResult("/tallies/"+id,corrected,200);
        assertThat(updated.path("createdAt")).isEqualTo(saved.path("createdAt"));
        assertThat(updated.path("updatedBy").asText()).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        assertThat(summary().path("categories").get(0).path("testedAndPriced").asLong()).isZero();
        mvc.perform(get(BASE+"/monthly-summary").session(staff).param("month","2024-02")).andExpect(jsonPath("$.divertedToOls").value(5));
        putResult("/tallies/"+id,corrected,409);
        postStatus("/tallies/preview?recordId="+id,corrected,admin,409);
        mvc.perform(delete(BASE+"/tallies/"+id).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(BASE+"/monthly-summary").session(staff).param("month","2024-02")).andExpect(jsonPath("$.divertedToOls").value(0));
        assertThat(count()).isEqualTo(1);
        mvc.perform(get(BASE+"/tallies/"+id).session(staff)).andExpect(status().isNotFound());
    }
    @Test void referenceCorrectionReplacesValueProtectsVersionAndRetainsCreationMetadata() throws Exception {
        var input=reference("0"); var saved=createReference(input); long id=saved.path("id").asLong();
        var correction=new SalesReferenceRequest(input.submissionId(),"2024-01",category,new BigDecimal("12"),saved.path("version").asLong());
        var updated=putResult("/sales-references/"+id,correction,200);
        assertThat(updated.path("soldLastMonthCount").asInt()).isEqualTo(12);
        assertThat(updated.path("createdBy")).isEqualTo(saved.path("createdBy"));
        assertThat(updated.path("updatedBy").asText()).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        putResult("/sales-references/"+id,correction,409);
        postStatus("/sales-references/preview?recordId="+id,correction,admin,409);
        mvc.perform(get(BASE+"/sales-references").session(staff).param("month",input.reportingMonth())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(BASE+"/sales-references").session(staff).param("month","2024-01")).andExpect(jsonPath("$[0].soldLastMonthCount").value(12));
    }
    @Test void bothResourcesEnforceStaffReadOnlyAfterSubmissionAndCsrf() throws Exception {
        Object[] inputs={tally("1",TallyType.STOCK_CULL,category),reference("0")}; String[] paths={"/tallies","/sales-references"};
        for(int i=0;i<paths.length;i++) {
            var body=json.writeValueAsString(inputs[i]);
            mvc.perform(post(BASE+paths[i]).session(staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            var result=mvc.perform(post(BASE+paths[i]).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
            long id=json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
            mvc.perform(get(BASE+paths[i]+"/"+id).session(staff)).andExpect(status().isOk());
            mvc.perform(put(BASE+paths[i]+"/"+id).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            mvc.perform(delete(BASE+paths[i]+"/"+id).session(staff).with(csrf())).andExpect(status().isForbidden());
            postStatus(paths[i]+"/preview?recordId="+id,inputs[i],staff,403);
        }
        mvc.perform(get(BASE+"/categories")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE+"/monthly-summary").session(staff).param("month","2024-13")).andExpect(status().isBadRequest());
    }
    @Test void concurrentTallyRetriesReturnOneIdAndChangedUuidDataConflicts() throws Exception {
        var input=tally("1",TallyType.STOCK_CULL,category); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Long> work=()->{start.await();return service.submit(input,System.getenv("INITIAL_STAFF_USERNAME")).record().id();};
            var a=executor.submit(work);var b=executor.submit(work);start.countDown();assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(count()).isEqualTo(1);
        postStatus("/tallies",new TallyRequest(input.submissionId(),input.recordDate(),null,TallyType.DIVERTED_TO_COLL,BigDecimal.ONE,null),staff,409);
        postStatus("/tallies",input,admin,409);
    }
    @Test void concurrentReferenceRetriesAndDifferentUuidMonthConflictAreSafe() throws Exception {
        var input=reference("0");var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Long> work=()->{start.await();return references.submit(input,System.getenv("INITIAL_STAFF_USERNAME")).record().id();};
            var a=executor.submit(work);var b=executor.submit(work);start.countDown();assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        postStatus("/sales-references",reference("1"),staff,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM electrical_testing_sales_reference",Integer.class)).isEqualTo(1);
    }
    @Test void largeMonthlySumsAndDateBoundariesRemainIndependent() throws Exception {
        for(int i=0;i<2;i++) createTally(new TallyRequest(UUID.randomUUID(),LocalDate.of(2024,2,29),category,TallyType.STOCK_CULL,new BigDecimal("2147483647"),null));
        createTally(new TallyRequest(UUID.randomUUID(),LocalDate.of(2024,3,1),category,TallyType.STOCK_CULL,BigDecimal.ONE,null));
        mvc.perform(get(BASE+"/monthly-summary").session(staff).param("month","2024-02")).andExpect(jsonPath("$.categories[0].stockCull").value(4294967294L));
        mvc.perform(get(BASE+"/monthly-summary").session(staff).param("month","2024-03")).andExpect(jsonPath("$.categories[0].stockCull").value(1));
        mvc.perform(get(BASE+"/tallies").session(staff).param("from","2024-03-01").param("to","2024-03-01")).andExpect(jsonPath("$.length()").value(1));
    }
    @Test void inactiveCategoriesAllowHistoricalCorrectionsAndConstraintsProtectRows() throws Exception {
        var saved=createTally(tally("1",TallyType.STOCK_CULL,category));
        jdbc.update("UPDATE electrical_testing_category SET active=false WHERE id=?",category);
        postStatus("/tallies",tally("1",TallyType.STOCK_CULL,category),staff,400);
        putResult("/tallies/"+saved.path("id").asLong(),new TallyRequest(UUID.randomUUID(),LocalDate.now(clock),category,TallyType.STOCK_CULL,BigDecimal.TEN,saved.path("version").asLong()),200);
        assertThatThrownBy(()->jdbc.update("UPDATE electrical_testing_tally SET count=0 WHERE id=?",saved.path("id").asLong())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE electrical_testing_tally SET category_id=NULL WHERE id=?",saved.path("id").asLong())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private TallyRequest tally(String n,TallyType type,Long category) {return new TallyRequest(UUID.randomUUID(),LocalDate.now(clock),category,type,new BigDecimal(n),null);}
    private SalesReferenceRequest reference(String n) {return new SalesReferenceRequest(UUID.randomUUID(),YearMonth.now(clock).toString(),category,new BigDecimal(n),null);}
    private int count(){return jdbc.queryForObject("SELECT count(*) FROM electrical_testing_tally",Integer.class);}
    private JsonNode createTally(TallyRequest input)throws Exception{return postResult("/tallies",input);}
    private JsonNode createReference(SalesReferenceRequest input)throws Exception{return postResult("/sales-references",input);}
    private JsonNode postResult(String path,Object input)throws Exception {
        var result=mvc.perform(post(BASE+path).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isCreated()).andReturn();return json.readTree(result.getResponse().getContentAsString());
    }
    private void postStatus(String path,Object input,MockHttpSession session,int expected)throws Exception {mvc.perform(post(BASE+path).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().is(expected));}
    private JsonNode putResult(String path,Object input,int expected)throws Exception {var result=mvc.perform(put(BASE+path).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().is(expected)).andReturn();return json.readTree(result.getResponse().getContentAsString());}
    private JsonNode summary()throws Exception {var result=mvc.perform(get(BASE+"/monthly-summary").session(staff).param("month",YearMonth.now(clock).toString())).andExpect(status().isOk()).andReturn();return json.readTree(result.getResponse().getContentAsString());}
    private MockHttpSession login(String role)throws Exception {return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",System.getenv("INITIAL_"+role+"_USERNAME")).param("password",System.getenv("INITIAL_"+role+"_PASSWORD"))).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);}
}
