package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.reporting.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"debug=false","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO"})
@Transactional
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class PaginationIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportingRepository repository;
    @Autowired tools.jackson.databind.ObjectMapper json;
    MockMvc mvc;
    long actor,category;
    @BeforeEach void setup() throws Exception {
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) { IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings()); }
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        actor=jdbc.queryForObject("select id from app_user where role='ADMIN' limit 1",Long.class);
        category=jdbc.queryForObject("select id from material_category where name='Iron and Steel'",Long.class);
        for(String table:List.of("crate_pallet","pickup","collectable","electrical_crate_pallet")) {
            String extra=table.equals("pickup")?",pickup_location":",gross_weight,tare_weight";
            String values=table.equals("pickup")?",'=unsafe, location'":",12.00,2.00";
            if(table.equals("electrical_crate_pallet") || table.equals("crate_pallet")) { extra+=",container_type_id"; values+=",(select id from container_type where name='Plastic Pallet')"; }
            // Use a valid approved tare for the selected electrical container.
            if(table.equals("electrical_crate_pallet")) values=values.replace("12.00,2.00","16.00,6.00");
            long id=jdbc.queryForObject("insert into "+table+"_record (submission_id,record_date,net_weight,created_by,updated_by,created_at,updated_at"+extra+") values (?,date '2001-02-28',10.00,?,?,now(),now()"+values+") returning id",Long.class,UUID.randomUUID(),actor,actor);
            long cat=table.equals("electrical_crate_pallet")?jdbc.queryForObject("select id from material_category where name='Everything Else'",Long.class):category;
            jdbc.update("insert into "+table+"_material ("+table+"_record_id,material_category_id,percentage,material_weight) values (?,?,100,10.00)",id,cat);
        }
        long salvage=jdbc.queryForObject("insert into salvage_record (submission_id,record_date,crew,time_up,kg_up,time_down,kg_down,created_by,updated_by,created_at,updated_at) values (?,date '2001-02-28','Crew',time '23:00',0,time '01:00',20,?,?,now(),now()) returning id",Long.class,UUID.randomUUID(),actor,actor);
        jdbc.update("insert into salvage_material values (?,'DOWN',?,100,20)",salvage,category);
        jdbc.update("insert into whitegoods_record (submission_id,record_date,whitegoods_count,kg_per_item,estimated_weight,created_by,updated_by,created_at,updated_at) values (?,date '2001-02-28',0,48,0,?,?,now(),now()), (?,date '2001-02-28',2,48,96,?,?,now(),now())",UUID.randomUUID(),actor,actor,UUID.randomUUID(),actor,actor);
        jdbc.update("insert into electrical_testing_tally (submission_id,record_date,tally_type,count,created_by,updated_by,created_at,updated_at) values (?,date '2001-02-28','DIVERTED_TO_OLS',3,?,?,now(),now())",UUID.randomUUID(),actor,actor);
        jdbc.update("insert into electrical_testing_sales_reference (submission_id,reporting_month,category_id,sold_last_month_count,created_by,updated_by,created_at,updated_at) values (?,date '2001-02-01',(select min(id) from electrical_testing_category),0,?,?,now(),now())",UUID.randomUUID(),actor,actor);
        jdbc.update("insert into ewaste_pickup_record(submission_id,record_date,organisation,entered_weight,total_tare_weight,net_weight,created_by,updated_by,created_at,updated_at) values (?,date '2001-02-28','Page source',12,0,12,?,?,now(),now())",UUID.randomUUID(),actor,actor);
        jdbc.update("insert into scrap_record(submission_id,reporting_month,iron_and_steel_weight,aluminium_weight,other_non_ferrous_weight,mixed_metals_weight,ewaste_weight,created_by,updated_by,created_at,updated_at) values (?,date '2001-02-01',1,0,0,0,0,?,?,now(),now())",UUID.randomUUID(),actor,actor);
        for(int n=0;n<61;n++)jdbc.update("insert into whitegoods_record(submission_id,record_date,whitegoods_count,kg_per_item,estimated_weight,created_by,updated_by,created_at,updated_at) values (?,date '2001-02-28',?,48,?, ?,?,now(),now())",UUID.randomUUID(),n,n*48,actor,actor);
    }

    private MockHttpServletRequestBuilder records() {return get("/api/records").with(user("staff").roles("STAFF")).param("from","2001-02-01").param("to","2001-02-28");}
    private MockHttpServletRequestBuilder report() {return get("/api/admin/reports/records").with(user("admin").roles("ADMIN")).param("from","2001-02-01").param("to","2001-02-28");}
    private tools.jackson.databind.JsonNode body(MockHttpServletRequestBuilder r)throws Exception{return json.readTree(mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    @Test void recordsDefaultsAndApprovedSizesHaveFilteredCounts()throws Exception {
        var page=body(records());assertThat(page.get("size").asInt()).isEqualTo(10);assertThat(page.get("items").size()).isEqualTo(10);assertThat(page.get("totalItems").asLong()).isEqualTo(71);assertThat(page.get("totalPages").asInt()).isEqualTo(8);
        for(int size:List.of(10,25,50)){var r=body(records().param("workflow","WHITEGOODS").param("size",""+size));assertThat(r.get("items").size()).isEqualTo(size);assertThat(r.get("totalItems").asLong()).isEqualTo(63);assertThat(r.get("totalPages").asLong()).isEqualTo((63+size-1)/size);}
    }
    @Test void pagesAcrossNineWorkflowsAreGlobalStableAndNeverTenPerWorkflow()throws Exception {
        var keys=new ArrayList<String>();var ordered=new ArrayList<String>();var workflows=new HashSet<String>();
        for(int page=0;page<8;page++){var r=body(records().param("page",""+page));assertThat(r.get("items").size()).isLessThanOrEqualTo(10);for(var item:r.get("items")){String w=item.get("workflow").asText();long id=item.get("record").get("id").asLong();workflows.add(w);keys.add(w+id);String date=w.equals("SCRAP")?item.get("record").get("reportingMonth").asText()+"-01":item.get("record").get("recordDate").asText();ordered.add(date+"|"+String.format("%020d",id)+"|"+w);}}
        assertThat(keys).hasSize(71).doesNotHaveDuplicates();assertThat(workflows).hasSize(9);
        var sorted=new ArrayList<>(ordered);sorted.sort((a,b)->{int d=b.substring(0,31).compareTo(a.substring(0,31));return d!=0?d:a.substring(31).compareTo(b.substring(31));});assertThat(ordered).isEqualTo(sorted);
        var expected=jdbc.queryForList("select id from whitegoods_record where deleted_at is null order by record_date desc,id desc limit 10 offset 10",Long.class);
        var second=body(records().param("workflow","WHITEGOODS").param("page","1"));var actual=new ArrayList<Long>();second.get("items").forEach(i->actual.add(i.get("record").get("id").asLong()));assertThat(actual).isEqualTo(expected);
    }
    @Test void dateWorkflowSoftDeletionAndScrapMonthPredicatesPrecedePaging()throws Exception {
        jdbc.update("update whitegoods_record set deleted_at=now(),deleted_by=? where whitegoods_count=60",actor);
        var r=body(records().param("workflow","WHITEGOODS"));assertThat(r.get("totalItems").asInt()).isEqualTo(62);
        mvc.perform(get("/api/records").with(user("staff").roles("STAFF")).param("workflow","WHITEGOODS").param("from","2001-03-01").param("to","2001-03-31")).andExpect(jsonPath("$.totalItems").value(0)).andExpect(jsonPath("$.totalPages").value(0)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(records().param("workflow","SCRAP").param("from","2001-02-20")).andExpect(jsonPath("$.totalItems").value(1));
    }
    @Test void invalidPagesSizesAndFiltersAreRejectedWithoutChangingRoles()throws Exception {
        for(String size:List.of("0","1","11","1000","-1")){mvc.perform(records().param("size",size)).andExpect(status().isBadRequest());mvc.perform(report().param("size",size)).andExpect(status().isBadRequest());}
        mvc.perform(records().param("page","-1")).andExpect(status().isBadRequest());mvc.perform(records().param("workflow","UNKNOWN")).andExpect(status().isBadRequest());mvc.perform(get("/api/records").with(user("staff").roles("STAFF")).param("from","2002-01-01").param("to","2001-01-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/records")).andExpect(status().isUnauthorized());mvc.perform(get("/api/records").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());mvc.perform(get("/api/admin/reports/records").with(user("staff").roles("STAFF"))).andExpect(status().isForbidden());
    }
    @Test void invalidFinalPageFallsBackAfterDeletionAndCorrectionsChangeCounts()throws Exception {
        var initial=body(records().param("workflow","WHITEGOODS").param("page","6"));assertThat(initial.get("page").asInt()).isEqualTo(6);assertThat(initial.get("items").size()).isEqualTo(3);
        jdbc.update("update whitegoods_record set deleted_at=now(),deleted_by=? where id in (select id from whitegoods_record order by record_date,id limit 3)",actor);
        mvc.perform(records().param("workflow","WHITEGOODS").param("page","6")).andExpect(jsonPath("$.page").value(5)).andExpect(jsonPath("$.items.length()").value(10)).andExpect(jsonPath("$.totalItems").value(60));
        jdbc.update("update whitegoods_record set record_date=date '2001-03-01' where whitegoods_count=59");mvc.perform(records().param("workflow","WHITEGOODS")).andExpect(jsonPath("$.totalItems").value(59));
    }
    @Test void detailedReportsPageFilterCountsAndReferencesRemainCorrect()throws Exception {
        var r=body(report().param("workflow","WHITEGOODS"));assertThat(r.get("rows").size()).isEqualTo(10);assertThat(r.get("size").asInt()).isEqualTo(10);assertThat(r.get("totalRows").asInt()).isEqualTo(63);
        for(int size:List.of(25,50))assertThat(body(report().param("workflow","WHITEGOODS").param("size",""+size)).get("rows").size()).isEqualTo(size);
        var second=body(report().param("workflow","WHITEGOODS").param("page","1"));assertThat(second.get("rows").get(0).get("id")).isNotEqualTo(r.get("rows").get(0).get("id"));
        mvc.perform(report().param("workflow","PICKUP").param("location","missing")).andExpect(jsonPath("$.totalRows").value(0)).andExpect(jsonPath("$.rows.length()").value(0));
        mvc.perform(report().param("workflow","ELECTRICAL_TESTING").param("from","2001-02-28")).andExpect(jsonPath("$.totalRows").value(2));
        mvc.perform(records().param("workflow","ELECTRICAL_TESTING")).andExpect(jsonPath("$.totalItems").value(1));
        var scrap=body(report().param("workflow","SCRAP"));assertThat(scrap.get("rows").size()).isEqualTo(6); // Fixed material fields are deterministic even with null category IDs.
        assertThat(scrap.get("rows")).isEqualTo(body(report().param("workflow","SCRAP")).get("rows"));
    }
    @Test void exportsIncludeEveryFilteredRowRegardlessOfScreenPage()throws Exception {
        String url="/api/admin/reports/export?from=2001-02-01&to=2001-02-28&workflow=WHITEGOODS&report=details&format=";
        String csv=mvc.perform(get(url+"csv&page=1&size=10").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(csv.lines().count()).isEqualTo(64);
        byte[] data=mvc.perform(get(url+"xlsx&page=1&size=10").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(data))){int rows=0;for(var row:book.getSheet("Whitegoods"))if(row.getCell(0)!=null&&row.getCell(0).toString().equals("RECORD"))rows++;assertThat(rows).isEqualTo(63);}
    }
    private MockHttpServletRequestBuilder summary(){return get("/api/admin/reports/summary").with(user("admin").roles("ADMIN")).param("from","2001-02-01").param("to","2001-02-28");}
    @Test void summaryPagesAggregateBeforePagingWithStableCompleteOrdering()throws Exception {
        var f=new ReportFilter(LocalDate.parse("2001-02-01"),LocalDate.parse("2001-02-28"),null,null,null,null,null);
        var expected=json.readTree(json.writeValueAsString(repository.totals(f)));int count=expected.size();assertThat(count).isGreaterThan(10);
        var first=body(summary());assertThat(first.get("totals").size()).isEqualTo(10);assertThat(first.get("size").asInt()).isEqualTo(10);
        for(int size:List.of(10,25,50)) {
            var rows=json.createArrayNode();for(int page=0;page<(count+size-1)/size;page++){
                var r=body(summary().param("page",""+page).param("size",""+size));assertThat(r.get("totalItems").asInt()).isEqualTo(count);assertThat(r.get("totalPages").asInt()).isEqualTo((count+size-1)/size);
                r.get("totals").forEach(rows::add);
            }assertThat(rows).isEqualTo(expected);
        }
        assertThat(body(summary()).get("totals")).isEqualTo(first.get("totals"));
        var last=body(summary().param("page","999"));assertThat(last.get("page").asInt()).isEqualTo((count-1)/10);
    }
    @Test void summaryFiltersEmptyPagesAndDeletedAggregatesRemainCorrect()throws Exception {
        var r=body(summary().param("workflow","PICKUP").param("location","missing"));assertThat(r.get("totalItems").asInt()).isZero();assertThat(r.get("totalPages").asInt()).isZero();assertThat(r.get("page").asInt()).isZero();
        var before=body(summary().param("workflow","WHITEGOODS"));assertThat(before.get("totalItems").asInt()).isEqualTo(before.get("totals").size());
        jdbc.update("update whitegoods_record set deleted_at=now(),deleted_by=?",actor);
        assertThat(body(summary().param("workflow","WHITEGOODS")).get("totalItems").asInt()).isZero();
        for(String size:List.of("0","1","100"))mvc.perform(summary().param("size",size)).andExpect(status().isBadRequest());
        mvc.perform(summary().param("page","-1")).andExpect(status().isBadRequest());
    }
    @Test void summaryAndMonthlyExportsIgnoreVisiblePages()throws Exception {
        int count=body(summary()).get("totalItems").asInt();
        String url="/api/admin/reports/export?from=2001-02-01&to=2001-02-28&report=summary&page=1&size=10&format=";
        var csv=mvc.perform(get(url+"csv").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(csv.lines().count()).isEqualTo(count+1);
        var data=mvc.perform(get(url+"xlsx").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(data))){int rows=0;for(var row:book.getSheet("Summary"))if(row.getCell(0)!=null&&Set.of("CRATE_PALLET","PICKUP","SALVAGE","COLLECTABLES","ELECTRICAL_CRATE_PALLET","WHITEGOODS","ELECTRICAL_TESTING","EWASTE_PICKUP","SCRAP").contains(row.getCell(0).toString()))rows++;assertThat(rows).isEqualTo(count);}
        int monthly=body(get("/api/admin/waste-levy-summary?month=2001-02").with(user("admin").roles("ADMIN"))).get("totals").size();
        var monthlyCsv=mvc.perform(get("/api/admin/waste-levy-summary/export?month=2001-02&format=csv&page=1&size=10").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(monthlyCsv.lines().count()).isEqualTo(monthly+1);
        var monthlyData=mvc.perform(get("/api/admin/waste-levy-summary/export?month=2001-02&format=xlsx&page=1&size=10").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(monthlyData))){int rows=0;for(var row:book.getSheet("Monthly Summary"))if(row.getCell(0)!=null&&Set.of("CRATE_PALLET","PICKUP","SALVAGE","COLLECTABLES","ELECTRICAL_CRATE_PALLET","WHITEGOODS","GENERAL_INTAKE","SCRAP").contains(row.getCell(0).toString()))rows++;assertThat(rows).isEqualTo(monthly);}
    }

}
