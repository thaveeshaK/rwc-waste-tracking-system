package au.org.resourcework.waste_tracking_api.reporting;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import static au.org.resourcework.waste_tracking_api.reporting.ReportData.*;

@Repository
public class ReportingRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ReportingRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // These sources are fixed application SQL, never request-supplied table or column names.
    private static String source(String table, String workflow, String kind, String joins,
            String container, String location, String crew, String direction, String categoryId,
            String category, String status, String kg, String gross, String tare, String percentage,
            String count, String factor, String date) {
        return "select '" + kind + "'::text row_type, '" + workflow + "'::text workflow, r.id, " + date + " record_date, "
                + container + "::text container, " + location + "::text location, " + crew + "::text crew, "
                + direction + "::text direction, " + categoryId + "::bigint category_id, " + category + "::text category, "
                + status + "::text status, " + kg + "::numeric kg, " + gross + "::numeric gross_kg, "
                + tare + "::numeric tare_kg, " + percentage + "::numeric percentage, " + count + "::bigint count, "
                + factor + "::numeric factor, " + (table.equals("salvage_record")?"r.time_up::text":"null::text") + " time_up, "
                + (table.equals("salvage_record")?"r.time_down::text":"null::text") + " time_down, r.submission_id::text submission_id, r.version, u.username created_by, r.created_at, v.username updated_by, r.updated_at from "
                + table + " r join app_user u on u.id=r.created_by join app_user v on v.id=r.updated_by "
                + joins + " where r.deleted_at is null";
    }

    private static final String DATA = buildData();
    private static String buildData() {
        var parts = new ArrayList<String>();
        String[] tables = {"crate_pallet", "pickup", "collectable", "electrical_crate_pallet"};
        String[] workflows = {"CRATE_PALLET", "PICKUP", "COLLECTABLES", "ELECTRICAL_CRATE_PALLET"};
        for (int i=0; i<tables.length; i++) {
            String t=tables[i], w=workflows[i];
            boolean container = i==0 || i==3;
            String join = container ? "join container_type ct on ct.id=r.container_type_id" : "";
            String name = container ? "ct.name" : "null";
            String loc = i==1 ? "r.pickup_location" : "null";
            parts.add(source(t+"_record",w,"RECORD",join,name,loc,"null","null","null","null","null",
                    "r.net_weight",i==1?"null":"r.gross_weight",i==1?"null":"r.tare_weight","null","null","null","r.record_date"));
            parts.add(source(t+"_record",w,"MATERIAL",join+" join "+t+"_material m on m."+t+"_record_id=r.id join material_category c on c.id=m.material_category_id",
                    name,loc,"null","null","c.id","c.name","null","m.material_weight","null","null","m.percentage","null","null","r.record_date"));
        }
        parts.add(source("salvage_record","SALVAGE","RECORD","","null","null","r.crew","null","null","null","null","null","null","null","null","null","null","r.record_date"));
        for (String d : List.of("UP","DOWN")) parts.add(source("salvage_record","SALVAGE","DIRECTION","","null","null","r.crew","'"+d+"'","null","null","null","r.kg_"+d.toLowerCase(),"null","null","null","null","null","r.record_date"));
        parts.add(source("salvage_record","SALVAGE","MATERIAL","join salvage_material m on m.salvage_record_id=r.id join material_category c on c.id=m.material_category_id",
                "null","null","r.crew","m.direction","c.id","c.name","null","m.material_weight","null","null","m.percentage","null","null","r.record_date"));
        parts.add(source("whitegoods_record","WHITEGOODS","RECORD","","null","null","null","null","null","null","null","r.estimated_weight","null","null","null","r.whitegoods_count","r.kg_per_item","r.record_date"));
        parts.add(source("electrical_testing_tally","ELECTRICAL_TESTING","RECORD","left join electrical_testing_category c on c.id=r.category_id",
                "null","null","null","null","c.id","c.name","r.tally_type","null","null","null","null","r.count","null","r.record_date"));
        parts.add(source("electrical_testing_sales_reference","ELECTRICAL_TESTING","REFERENCE","join electrical_testing_category c on c.id=r.category_id",
                "null","null","null","null","c.id","c.name","'SOLD_LAST_MONTH'","null","null","null","null","r.sold_last_month_count","null","r.reporting_month"));
        return String.join(" union all ", parts);
    }

    private String filtered(ReportFilter f, MapSqlParameterSource p) {
        p.addValue("from",f.from()).addValue("end",f.to().plusDays(1));
        String where=" record_date >= :from and record_date < :end";
        if (f.workflow()!=null) { where+=" and workflow=:workflow"; p.addValue("workflow",f.workflow()); }
        if (f.location()!=null) { where+=" and position(lower(:location) in lower(location))>0"; p.addValue("location",f.location()); }
        if (f.testingCategoryId()!=null) { where+=" and category_id=:testingCategory"; p.addValue("testingCategory",f.testingCategoryId()); }
        if (f.materialCategoryId()!=null) {
            where+=" and row_type='MATERIAL' and category_id=:category";
            p.addValue("category",f.materialCategoryId());
        }
        if (f.direction()!=null) { where+=" and direction=:direction"; p.addValue("direction",f.direction()); }
        // Reference periods use the reporting month, not an invented event date. Include all touched months.
        String refs = f.materialCategoryId()==null && f.direction()==null && f.location()==null
                && (f.workflow()==null || f.workflow().equals("ELECTRICAL_TESTING"))
                ? " or (row_type='REFERENCE' and record_date >= :monthStart and record_date < :end" + (f.testingCategoryId()==null?"":" and category_id=:testingCategory") + ")" : "";
        p.addValue("monthStart", f.from().withDayOfMonth(1));
        return "with data as ("+DATA+"), filtered as (select * from data where ("+where+")"+refs+") ";
    }

    public List<Total> totals(ReportFilter f) {
        var p=new MapSqlParameterSource();
        String sql=filtered(f,p)+"""
            select workflow, direction, container, category_id, category, metric, unit, value from (
              select workflow,direction,container,category_id,category,
                case when row_type='MATERIAL' then 'Material weight' when workflow='WHITEGOODS' then 'Estimated weight' else 'Net weight' end metric,
                'kg' unit,sum(kg) value from filtered where kg is not null
                group by workflow,direction,container,category_id,category,row_type
              union all
              select workflow,direction,container,null,null,'Records','records',count(distinct id)::numeric
                from filtered where row_type <> 'REFERENCE' group by workflow,direction,container
              union all
              select workflow,null,null,category_id,category,coalesce(status,'Whitegoods count'),'count',sum(count)::numeric
                from filtered where count is not null and row_type <> 'REFERENCE' group by workflow,category_id,category,status
              union all
              select workflow,null,null,null,null,'Applied factor','kg/item',factor from filtered where workflow='WHITEGOODS' group by workflow,factor
            ) t order by workflow,direction nulls first,container nulls first,category_id nulls first,metric
            """;
        return jdbc.query(sql,p,(rs,n)->new Total(rs.getString("workflow"),rs.getString("direction"),rs.getString("container"),
                rs.getObject("category_id",Long.class),rs.getString("category"),rs.getString("metric"),rs.getString("unit"),decimal(rs,"value")));
    }

    public List<Row> rows(ReportFilter f, Integer limit, int offset) {
        var p=new MapSqlParameterSource();
        String sql=filtered(f,p)+"select * from filtered order by record_date desc,workflow,id desc,row_type,direction,category_id";
        if (limit!=null) { sql+=" limit :limit offset :offset"; p.addValue("limit",limit).addValue("offset",offset); }
        return jdbc.query(sql,p,(rs,n)->row(rs));
    }
    public long rowCount(ReportFilter f) {
        var p=new MapSqlParameterSource();
        return jdbc.queryForObject(filtered(f,p)+"select count(*) from filtered",p,Long.class);
    }
    public List<Row> recent(int limit) {
        return jdbc.query("with data as ("+DATA+") select * from data where row_type='RECORD' order by created_at desc,workflow,id desc limit :limit",
                Map.of("limit",limit),(rs,n)->row(rs));
    }
    public long submitted(Instant start, Instant end) {
        return jdbc.queryForObject("with data as ("+DATA+") select count(*) from data where row_type='RECORD' and created_at>=:start and created_at<:end",
                new MapSqlParameterSource().addValue("start",java.sql.Timestamp.from(start)).addValue("end",java.sql.Timestamp.from(end)),Long.class);
    }
    public List<Activity> activity(ReportFilter f) {
        var p=new MapSqlParameterSource();
        return jdbc.query(filtered(f,p)+"select workflow,count(*) n from filtered where row_type='RECORD' group by workflow order by workflow",p,
                (rs,n)->new Activity(rs.getString("workflow"),rs.getLong("n")));
    }
    public List<Trend> trend(ReportFilter f) {
        var p=new MapSqlParameterSource();
        return jdbc.query(filtered(f,p)+"select to_char(record_date,'YYYY-MM') as reporting_month,workflow,count(*) n from filtered where row_type='RECORD' group by reporting_month,workflow order by reporting_month,workflow",p,
                (rs,n)->new Trend(rs.getString("reporting_month"),rs.getString("workflow"),rs.getLong("n")));
    }
    public Options options() {
        return new Options(jdbc.query("select id,name from material_category order by id",Map.of(),(r,n)->new Option(r.getLong(1),r.getString(2))),
                jdbc.query("select id,name from electrical_testing_category order by display_order",Map.of(),(r,n)->new Option(r.getLong(1),r.getString(2))));
    }
    public List<Total> categoryTemplates() {
        var templates = new ArrayList<>(jdbc.query("""
            select a.context,c.id,c.name from material_category_applicability a
            join material_category c on c.id=a.material_category_id
            where a.context in ('CRATE_PALLET','PICKUP','COLLECTABLES','SALVAGE_UP','SALVAGE_DOWN','ELECTRICAL_CRATE_PALLET')
            order by a.context,c.id
            """,Map.of(),(rs,n)-> {
                String context=rs.getString(1);
                String direction=context.startsWith("SALVAGE_")?context.substring(8):null;
                return new Total(direction==null?context:"SALVAGE",direction,null,rs.getLong(2),rs.getString(3),"Material weight","kg","0.00");
            }));
        templates.stream().filter(t->t.workflow().equals("CRATE_PALLET")).toList().forEach(t->templates.add(new Total("GENERAL_INTAKE",null,null,t.categoryId(),t.category(),"Material weight","kg","0.00")));
        return templates;
    }
    private static String decimal(ResultSet r,String field) throws SQLException {
        BigDecimal value=r.getBigDecimal(field); return value==null?null:value.toPlainString();
    }
    private static Row row(ResultSet r) throws SQLException {
        return new Row(r.getString("row_type"),r.getString("workflow"),r.getLong("id"),r.getDate("record_date").toLocalDate(),
                r.getString("container"),r.getString("location"),r.getString("crew"),r.getString("direction"),r.getObject("category_id",Long.class),
                r.getString("category"),r.getString("status"),decimal(r,"kg"),decimal(r,"gross_kg"),decimal(r,"tare_kg"),decimal(r,"percentage"),
                r.getObject("count",Long.class),decimal(r,"factor"),r.getString("time_up"),r.getString("time_down"),r.getString("submission_id"),r.getLong("version"),r.getString("created_by"),r.getTimestamp("created_at").toInstant(),
                r.getString("updated_by"),r.getTimestamp("updated_at").toInstant());
    }
}
