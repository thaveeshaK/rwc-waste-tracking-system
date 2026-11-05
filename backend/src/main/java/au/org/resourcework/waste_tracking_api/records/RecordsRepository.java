package au.org.resourcework.waste_tracking_api.records;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class RecordsRepository {
    public record Key(String workflow, Long id) {}
    private final NamedParameterJdbcTemplate jdbc;
    public RecordsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    // One database result set across all workflows; no per-workflow paging/merging.
    private static final String SOURCES="""
            select id,'CRATE_PALLET'::text workflow,record_date record_date from crate_pallet_record where deleted_at is null union all
            select id,'SALVAGE'::text workflow,record_date record_date from salvage_record where deleted_at is null union all
            select id,'PICKUP'::text workflow,record_date record_date from pickup_record where deleted_at is null union all
            select id,'ELECTRICAL_CRATE_PALLET'::text workflow,record_date record_date from electrical_crate_pallet_record where deleted_at is null union all
            select id,'COLLECTABLES'::text workflow,record_date record_date from collectable_record where deleted_at is null union all
            select id,'WHITEGOODS'::text workflow,record_date record_date from whitegoods_record where deleted_at is null union all
            select id,'ELECTRICAL_TESTING'::text workflow,record_date record_date from electrical_testing_tally where deleted_at is null union all
            select id,'EWASTE_PICKUP'::text workflow,record_date record_date from ewaste_pickup_record where deleted_at is null union all
            select id,'SCRAP'::text workflow,reporting_month record_date from scrap_record where deleted_at is null
            """;
    private String filtered() {
        return "with data as ("+SOURCES+") select * from data where (cast(:workflow as text) is null or workflow=:workflow) "
            + "and record_date >= case when workflow='SCRAP' then cast(:monthStart as date) else cast(:from as date) end and record_date <= :to";
    }
    private MapSqlParameterSource parameters(String workflow,LocalDate from,LocalDate to) {
        return new MapSqlParameterSource().addValue("workflow",workflow).addValue("from",from).addValue("monthStart",from.withDayOfMonth(1)).addValue("to",to);
    }
    public long count(String workflow,LocalDate from,LocalDate to) {
        return jdbc.queryForObject("select count(*) from ("+filtered()+") matching",parameters(workflow,from,to),Long.class);
    }
    public List<Key> page(String workflow,LocalDate from,LocalDate to,int page,int size) {
        return jdbc.query(filtered()+" order by record_date desc,id desc,workflow limit :size offset :offset",
            parameters(workflow,from,to).addValue("size",size).addValue("offset",(long)page*size),
            (r,n)->new Key(r.getString("workflow"),r.getLong("id")));
    }
}
