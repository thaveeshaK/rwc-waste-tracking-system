package au.org.resourcework.waste_tracking_api.operations;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationsRepository {
    private final JdbcTemplate jdbc;
    public OperationsRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<RecentSubmission> recent() {
        // Fixed sources only. Staff can view all submitted operational records, including Admin entries.
        return jdbc.query("""
            select * from (
              select id,record_date,'CRATE_PALLET' workflow,container_name details,net_weight weight_kg,null::bigint count,created_at
                from crate_pallet_record where deleted_at is null
              union all
              select id,record_date,'SALVAGE',crew || ' / Up ' || kg_up || ' kg / Down ' || kg_down || ' kg',null,null,created_at
                from salvage_record where deleted_at is null
              union all
              select id,record_date,'PICKUP',pickup_location,net_weight,null,created_at
                from pickup_record where deleted_at is null
              union all
              select id,record_date,'ELECTRICAL_CRATE_PALLET',container_name,net_weight,null,created_at
                from electrical_crate_pallet_record where deleted_at is null
              union all
              select id,record_date,'COLLECTABLES','Collectable Tub',net_weight,null,created_at
                from collectable_record where deleted_at is null
              union all
              select id,record_date,'WHITEGOODS','Received Whitegoods',estimated_weight,whitegoods_count,created_at
                from whitegoods_record where deleted_at is null
              union all
              select r.id,r.record_date,'ELECTRICAL_TESTING',coalesce(c.name || ' / ','') ||
                case r.tally_type when 'UNTESTED_BROKEN_FAILED' then 'Untested / Broken / Failed'
                  when 'TESTED_AND_PRICED' then 'Tested and Priced' when 'STOCK_CULL' then 'Stock Cull'
                  when 'DIVERTED_TO_OLS' then 'Diverted to OLS' when 'DIVERTED_TO_COLL' then 'Diverted to Coll' end,
                null,r.count,r.created_at from electrical_testing_tally r
                left join electrical_testing_category c on c.id=r.category_id where r.deleted_at is null
              union all
              select id,record_date,'EWASTE_PICKUP',organisation,net_weight,null,created_at
                from ewaste_pickup_record where deleted_at is null
              union all
              select id,reporting_month,'SCRAP','Reporting month ' || to_char(reporting_month,'YYYY-MM'),iron_and_steel_weight+aluminium_weight+other_non_ferrous_weight+mixed_metals_weight+ewaste_weight,null,created_at from scrap_record where deleted_at is null
            ) recent order by created_at desc,workflow,id desc limit 5
            """,(rs,n)->new RecentSubmission(rs.getLong("id"),rs.getDate("record_date").toLocalDate(),
                rs.getString("workflow"),rs.getString("details"),rs.getBigDecimal("weight_kg")==null?null:rs.getBigDecimal("weight_kg").toPlainString(),rs.getObject("count",Long.class)));
    }
}
