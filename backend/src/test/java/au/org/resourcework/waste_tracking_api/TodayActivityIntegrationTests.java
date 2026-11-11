package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.reporting.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class TodayActivityIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportingRepository repository;
    final LocalDate day=LocalDate.of(1998,10,4); // Hobart daylight-saving transition.
    final ZoneId zone=ZoneId.of("Australia/Hobart");
    long actor;
    @BeforeEach void setup() throws Exception {
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings());}
        actor=jdbc.queryForObject("select id from app_user where role='ADMIN' limit 1",Long.class);
    }
    long insert(String table,Map<String,Object> fields) {
        var data=new LinkedHashMap<String,Object>(fields);
        data.put("submission_id",UUID.randomUUID());data.put("created_by",actor);data.put("updated_by",actor);
        data.put("created_at",java.sql.Timestamp.from(day.atStartOfDay(zone).toInstant().plusSeconds(1)));
        data.put("updated_at",data.get("created_at"));
        return jdbc.queryForObject("insert into "+table+" ("+String.join(",",data.keySet())+") values ("+String.join(",",Collections.nCopies(data.size(),"?"))+") returning id",Long.class,data.values().toArray());
    }
    void weighted(String table,String group,String value) {
        var fields=new HashMap<String,Object>();fields.put("record_date",day);fields.put("net_weight",new BigDecimal(value));
        if(table.equals("pickup_record")) fields.put("pickup_location","Test location");
        else {
            fields.put("tare_weight",new BigDecimal("2.00"));fields.put("gross_weight",new BigDecimal(value).add(new BigDecimal("2.00")));
            if(!table.equals("collectable_record")) {
                long container=jdbc.queryForObject("insert into container_type(name,tare_weight,summary_group) values (?,2,?) returning id",Long.class,"Not classified by name "+UUID.randomUUID(),group);
                fields.put("container_type_id",container);
            }
        }
        insert(table,fields);
    }
    List<ReportData.DailyActivity> activity() {return repository.todayActivity(day,day.atStartOfDay(zone).toInstant(),day.plusDays(1).atStartOfDay(zone).toInstant());}
    Map<String,String> metrics(List<ReportData.DailyActivity> result,String workflow) {
        var values=new HashMap<String,String>();result.stream().filter(a->a.workflow().equals(workflow)).findFirst().orElseThrow().metrics().forEach(m->values.put(m.label(),m.value()));return values;
    }
    @Test void allDailyAggregatesUseStoredValuesAndHistoricalGroups() {
        for(String table:List.of("crate_pallet_record","electrical_crate_pallet_record")) {
            weighted(table,"CRATE","10.01");weighted(table,"PALLET","20.02");weighted(table,"OTHER","30.03");
        }
        // Changing configuration must not alter the classification already snapshotted by either workflow.
        jdbc.update("update container_type set summary_group='OTHER' where name like 'Not classified by name %'");
        weighted("pickup_record",null,"40.04");weighted("collectable_record",null,"50.05");
        insert("ewaste_pickup_record",Map.of("record_date",day,"organisation","Test","entered_weight",100,"total_tare_weight",25,"net_weight",75));
        insert("salvage_record",Map.of("record_date",day,"crew","Crew","time_up",java.sql.Time.valueOf("09:00:00"),"time_down",java.sql.Time.valueOf("10:00:00"),"kg_up",new BigDecimal("12.34"),"kg_down",new BigDecimal("56.78")));
        for(int count:List.of(0,2,3)) insert("whitegoods_record",Map.of("record_date",day,"whitegoods_count",count,"kg_per_item",48,"estimated_weight",count*48));
        long category=jdbc.queryForObject("select min(id) from electrical_testing_category",Long.class);
        for(String type:List.of("TESTED_AND_PRICED","UNTESTED_BROKEN_FAILED","STOCK_CULL","DIVERTED_TO_OLS","DIVERTED_TO_COLL")) {
            var fields=new HashMap<String,Object>();fields.put("record_date",day);fields.put("tally_type",type);fields.put("count",type.equals("TESTED_AND_PRICED")?4:type.equals("UNTESTED_BROKEN_FAILED")?7:99);
            if(!type.startsWith("DIVERTED")) fields.put("category_id",category);insert("electrical_testing_tally",fields);
        }
        insert("electrical_testing_sales_reference",Map.of("reporting_month",day.withDayOfMonth(1),"category_id",category,"sold_last_month_count",999));
        var result=activity();assertThat(result).hasSize(9);
        for(String w:List.of("CRATE_PALLET","ELECTRICAL_CRATE_PALLET")) assertThat(metrics(result,w)).containsExactlyInAnyOrderEntriesOf(Map.of("Crates","1","Pallets","1","Combined weight","60.06"));
        assertThat(metrics(result,"SALVAGE")).containsExactlyInAnyOrderEntriesOf(Map.of("Trips","1","Salvage Up","12.34","Salvage Down","56.78"));
        assertThat(metrics(result,"PICKUP")).containsEntry("Trips","1").containsEntry("Combined weight","40.04");
        assertThat(metrics(result,"EWASTE_PICKUP")).containsEntry("Trips","1").containsEntry("Combined weight","75.00");
        assertThat(metrics(result,"COLLECTABLES")).containsEntry("Trips","1").containsEntry("Combined weight","50.05");
        assertThat(metrics(result,"WHITEGOODS")).containsEntry("Items","5");
        assertThat(metrics(result,"ELECTRICAL_TESTING")).containsExactlyInAnyOrderEntriesOf(Map.of("Working","4","Untested / Broken / Failed","7"));
        // Soft deletion removes every workflow contribution without physically deleting anything.
        for(String table:List.of("crate_pallet_record","electrical_crate_pallet_record","pickup_record","collectable_record","ewaste_pickup_record","salvage_record","whitegoods_record","electrical_testing_tally")) jdbc.update("update "+table+" set deleted_by=?,deleted_at=now() where record_date=?",actor,day);
        assertThat(activity().stream().flatMap(a->a.metrics().stream()).map(m->new BigDecimal(m.value()))).allMatch(v->v.signum()==0);
    }
    @Test void scrapCountsEntryDayAndReportingMonthSeparatelyWithExclusiveHobartBoundaries() {
        var fields=Map.<String,Object>of("reporting_month",day.withDayOfMonth(1),"iron_and_steel_weight",1,"aluminium_weight",0,"other_non_ferrous_weight",0,"mixed_metals_weight",0,"ewaste_weight",0);
        long current=insert("scrap_record",fields);long previous=insert("scrap_record",fields);long next=insert("scrap_record",fields);
        var historical=new HashMap<>(fields);historical.put("reporting_month",day.minusMonths(1).withDayOfMonth(1));
        long otherMonth=insert("scrap_record",historical);
        var start=day.atStartOfDay(zone).toInstant();var end=day.plusDays(1).atStartOfDay(zone).toInstant();
        jdbc.update("update scrap_record set created_at=? where id=?",java.sql.Timestamp.from(start),current);
        jdbc.update("update scrap_record set created_at=? where id=?",java.sql.Timestamp.from(start.minusNanos(1000)),previous);
        jdbc.update("update scrap_record set created_at=? where id=?",java.sql.Timestamp.from(end),next);
        assertThat(metrics(activity(),"SCRAP")).containsEntry("Records today","2").containsEntry("Records this month","3");
        jdbc.update("update scrap_record set deleted_by=?,deleted_at=now() where id=?",actor,otherMonth);
        assertThat(metrics(activity(),"SCRAP")).containsEntry("Records today","1").containsEntry("Records this month","3");
        jdbc.update("update scrap_record set deleted_by=?,deleted_at=now() where id=?",actor,current);
        assertThat(metrics(activity(),"SCRAP")).containsEntry("Records today","0").containsEntry("Records this month","2");
    }
    @Test void emptyDayAndOtherOperationalDatesProduceZeroMetrics() {
        weighted("pickup_record",null,"9.99");jdbc.update("update pickup_record set record_date=? where record_date=?",day.minusDays(1),day);
        assertThat(activity()).hasSize(9);
        assertThat(activity().stream().flatMap(a->a.metrics().stream()).map(m->new BigDecimal(m.value()))).allMatch(v->v.signum()==0);
    }

    @Test void DATE_02_fallBackDayIncludesBothRepeatedHoursAndExcludesAdjacentDays() {
        var date=LocalDate.of(2026,4,5);
        var start=date.atStartOfDay(zone).toInstant();var end=date.plusDays(1).atStartOfDay(zone).toInstant();
        assertThat(Duration.between(start,end)).isEqualTo(Duration.ofHours(25));
        assertThat(start).isEqualTo(Instant.parse("2026-04-04T13:00:00Z"));
        assertThat(end).isEqualTo(Instant.parse("2026-04-05T14:00:00Z"));
        var repeated=LocalDateTime.of(2026,4,5,2,30);
        var offsets=zone.getRules().getValidOffsets(repeated);
        assertThat(offsets).containsExactly(ZoneOffset.ofHours(11),ZoneOffset.ofHours(10));
        var instants=List.of(start.minusNanos(1000),start,repeated.toInstant(offsets.get(0)),repeated.toInstant(offsets.get(1)),end.minusNanos(1000),end);
        for(var instant:instants) {
            long id=insert("scrap_record",Map.of("reporting_month",date.withDayOfMonth(1),"iron_and_steel_weight",1,"aluminium_weight",0,"other_non_ferrous_weight",0,"mixed_metals_weight",0,"ewaste_weight",0));
            jdbc.update("update scrap_record set created_at=? where id=?",java.sql.Timestamp.from(instant),id);
        }
        assertThat(repeated.toInstant(offsets.get(0)).atZone(zone).toLocalDate()).isEqualTo(date);
        assertThat(repeated.toInstant(offsets.get(1)).atZone(zone).toLocalDate()).isEqualTo(date);
        assertThat(metrics(repository.todayActivity(date,start,end),"SCRAP")).containsEntry("Records today","4");
        assertThat(metrics(repository.todayActivity(date.minusDays(1),date.minusDays(1).atStartOfDay(zone).toInstant(),start),"SCRAP")).containsEntry("Records today","1");
        assertThat(metrics(repository.todayActivity(date.plusDays(1),end,date.plusDays(2).atStartOfDay(zone).toInstant()),"SCRAP")).containsEntry("Records today","1");
        // Exercise the service's range construction too, not only a caller-supplied range.
        var report=new ReportService(repository,Clock.fixed(repeated.toInstant(offsets.get(1)),zone),null).overview("2026-04");
        assertThat(report.today()).isEqualTo("2026-04-05");
        assertThat(metrics(report.todayActivity(),"SCRAP")).containsEntry("Records today","4");
    }
}
