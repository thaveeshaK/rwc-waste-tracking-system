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
class ReportingIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportingRepository repository;
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
    }
    private MockHttpServletRequestBuilder report(String path) { return get("/api/admin"+path).with(user("admin").roles("ADMIN")).param("from","2001-02-01").param("to","2001-02-28"); }
    @Test void adminBoundaryProtectsEverySurface() throws Exception {
        for(String path:List.of("/overview","/reports/summary","/reports/records","/reports/options","/reports/electrical-testing","/reports/export","/waste-levy-summary","/waste-levy-summary/export")) {
            mvc.perform(get("/api/admin"+path)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin"+path).with(user("staff").roles("STAFF"))).andExpect(status().isForbidden());
        }
        mvc.perform(report("/overview")).andExpect(status().isOk());
    }
    @Test void aggregationSeparatesWeightsDirectionsAndCounts() throws Exception {
        var f=new ReportFilter(LocalDate.of(2001,2,1),LocalDate.of(2001,2,28),null,null,null,null,null);
        var totals=repository.totals(f);
        assertThat(totals).anySatisfy(t->{assertThat(t.workflow()).isEqualTo("SALVAGE");assertThat(t.direction()).isEqualTo("UP");assertThat(t.value()).isEqualTo("0.00");});
        assertThat(totals).anySatisfy(t->{assertThat(t.workflow()).isEqualTo("WHITEGOODS");assertThat(t.metric()).isEqualTo("Estimated weight");assertThat(t.value()).isEqualTo("96.00");});
        assertThat(repository.activity(f).stream().mapToLong(ReportData.Activity::count).sum()).isEqualTo(8);
        assertThat(totals).noneMatch(t->"SOLD_LAST_MONTH".equals(t.metric()));
        mvc.perform(report("/reports/summary")).andExpect(status().isOk());
    }
    @Test void materialLocationAndDirectionFiltersAreAppliedAndValidated() throws Exception {
        mvc.perform(report("/reports/records").param("workflow","PICKUP").param("location","unsafe").param("materialCategoryId",Long.toString(category)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(1)).andExpect(jsonPath("$.rows[0].kg").value("10.00"));
        mvc.perform(report("/reports/summary").param("workflow","WHITEGOODS").param("materialCategoryId","1")).andExpect(status().isBadRequest());
        mvc.perform(report("/reports/summary").param("location","x")).andExpect(status().isBadRequest());
        mvc.perform(report("/reports/summary").param("direction","UP")).andExpect(status().isBadRequest());
        mvc.perform(report("/reports/summary").param("workflow","SALVAGE").param("direction","UP")).andExpect(status().isOk());
        mvc.perform(report("/reports/summary").param("workflow","SCRAP")).andExpect(status().isBadRequest());
    }
    @Test void monthlyCombinesOnlyApprovedGeneralIntake() throws Exception {
        String body=mvc.perform(report("/waste-levy-summary").param("month","2001-02")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("GENERAL_INTAKE","30.00","E-waste Pickup, Scrap").doesNotContain("DIVERTED_TO_OLS");
        mvc.perform(report("/waste-levy-summary").param("month","2001-13")).andExpect(status().isBadRequest());
    }
    @Test void correctionsAndDeletionChangeReportsWithoutStoredMonthlyTotals() throws Exception {
        jdbc.update("update pickup_record set record_date=date '2001-03-01' where record_date=date '2001-02-28'");
        jdbc.update("update whitegoods_record set deleted_at=now(),deleted_by=? where record_date=date '2001-02-28'",actor);
        mvc.perform(report("/reports/records").param("workflow","PICKUP")).andExpect(jsonPath("$.totalRows").value(0));
        mvc.perform(report("/reports/records").param("workflow","WHITEGOODS")).andExpect(jsonPath("$.totalRows").value(0));
    }
    @Test void referencesRemainSeparateAndIncludeTouchedReportingMonths() throws Exception {
        mvc.perform(report("/reports/electrical-testing").param("from","2001-02-28"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(2));
        mvc.perform(report("/reports/records").param("size","1")).andExpect(jsonPath("$.rows.length()").value(1));
        mvc.perform(report("/reports/records").param("size","201")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/reports/records").with(user("admin").roles("ADMIN")).param("from","2001-03-01").param("to","2001-02-28")).andExpect(status().isBadRequest());
    }
    @Test void csvExportsMatchFiltersAndProtectUserText() throws Exception {
        String csv=mvc.perform(report("/reports/export").param("report","details").param("format","csv").param("workflow","PICKUP").param("location","unsafe"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("report-details-2001-02-01-to-2001-02-28.csv")))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("'=unsafe, location","10.00","submission_id","Australia/Hobart").doesNotContain("COLLECTABLES","WHITEGOODS");
        mvc.perform(report("/reports/export").param("format","pdf")).andExpect(status().isBadRequest());
        mvc.perform(report("/reports/export").param("report","electrical-testing")).andExpect(status().isBadRequest());
    }
    @Test void excelContainsTypedSafeValuesAndSeparateReferences() throws Exception {
        byte[] bytes=mvc.perform(report("/reports/export").param("report","details").param("format","xlsx"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            assertThat(book.getSheet("Detailed Records")).isNotNull();
            assertThat(book.getSheet("Material Breakdown")).isNotNull();
            assertThat(book.getSheet("Whitegoods")).isNotNull();
            assertThat(book.getSheet("Sales References")).isNotNull();
            boolean found=false;
            for(var sheet:book) for(var row:sheet) for(var cell:row) {
                assertThat(cell.getCellType()).isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
                if(cell.getCellType()==org.apache.poi.ss.usermodel.CellType.STRING && cell.getStringCellValue().equals("=unsafe, location")) found=true;
            }
            assertThat(found).isTrue();
        }
    }
    @Test void monthlyExportsHaveStableNamesNotesAndNoLegacyCalculations() throws Exception {
        String csv=mvc.perform(report("/waste-levy-summary/export").param("month","2001-02"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("waste-levy-summary-2001-02.csv")))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("GENERAL_INTAKE","30.00","carry-over calculations are excluded");
        byte[] bytes=mvc.perform(report("/waste-levy-summary/export").param("month","2001-02").param("format","xlsx"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(1);
            assertThat(book.getSheetName(0)).isEqualTo("Monthly Summary");
        }
    }
    @Test void categoryAggregationUsesStoredRoundingAndDoesNotMultiplyNetWeight() throws Exception {
        long other=jdbc.queryForObject("select id from material_category where name='Timber'",Long.class);
        long id=jdbc.queryForObject("select id from pickup_record where record_date=date '2001-02-28'",Long.class);
        jdbc.update("update pickup_material set percentage=50,material_weight=5.01 where pickup_record_id=?",id);
        jdbc.update("insert into pickup_material values (?,?,50,5.01)",id,other);
        var totals=repository.totals(new ReportFilter(LocalDate.of(2001,2,1),LocalDate.of(2001,2,28),"PICKUP",null,null,null,null));
        assertThat(totals).anySatisfy(t->{assertThat(t.metric()).isEqualTo("Net weight");assertThat(t.value()).isEqualTo("10.00");});
        assertThat(totals.stream().filter(t->t.metric().equals("Material weight")).map(t->new java.math.BigDecimal(t.value())).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add)).isEqualByComparingTo("10.02");
        assertThat(totals).anySatisfy(t->{assertThat(t.metric()).isEqualTo("Records");assertThat(t.value()).isEqualTo("1");});
    }
    @Test void financialYearLeapDayAndEmptyPeriodsUseOperationalDates() throws Exception {
        mvc.perform(get("/api/admin/reports/records").with(user("admin").roles("ADMIN")).param("from","2000-07-01").param("to","2001-06-30").param("workflow","WHITEGOODS"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(2));
        mvc.perform(get("/api/admin/reports/records").with(user("admin").roles("ADMIN")).param("from","2000-02-29").param("to","2000-02-29"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(0));
        mvc.perform(report("/waste-levy-summary").param("month","2000-02")).andExpect(status().isOk());
    }

}
