package au.org.resourcework.waste_tracking_api.settings;

import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import static au.org.resourcework.waste_tracking_api.settings.SettingsData.*;

@Repository
public class SettingsRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public SettingsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Configuration configuration() {
        return jdbc.queryForObject("select s.*,u.username from application_settings s left join app_user u on u.id=s.updated_by where s.id=1",Map.of(),(r,n)->
            new Configuration(r.getLong("version"),r.getString("organisation_name"),r.getString("site_name"),r.getString("report_display_name"),r.getString("display_unit"),r.getString("report_period"),r.getInt("financial_year_start_month"),r.getBigDecimal("whitegoods_kg_per_item"),r.getString("username"),r.getTimestamp("updated_at")==null?null:r.getTimestamp("updated_at").toInstant()));
    }
    public int update(Configuration c,String username) {
        return jdbc.update("""
            update application_settings set organisation_name=:name,site_name=:site,report_display_name=:report,
            display_unit=:unit,report_period=:period,financial_year_start_month=:month,whitegoods_kg_per_item=:factor,
            updated_by=(select id from app_user where username=:actor),updated_at=now(),version=version+1
            where id=1 and version=:version
            """,new MapSqlParameterSource().addValue("name",c.organisationName().trim()).addValue("site",c.siteName().trim())
                .addValue("report",c.reportDisplayName().trim()).addValue("unit",c.displayUnit()).addValue("period",c.reportPeriod())
                .addValue("month",c.financialYearStartMonth()).addValue("factor",c.whitegoodsKgPerItem()).addValue("actor",username).addValue("version",c.version()));
    }
    // The table choice is internal and restricted to these two existing reference tables.
    private String table(boolean container) { return container?"container_type":"material_category"; }
    private String link(boolean container) { return table(container)+"_applicability"; }
    private String key(boolean container) { return container?"container_type_id":"material_category_id"; }
    public List<Reference> references(boolean container) {
        String sql="select r.id,r.settings_version,r.name,r.active,"+(container?"r.tare_weight,r.summary_group":"null::numeric tare_weight,null::text summary_group")+", coalesce(string_agg(a.context,',' order by a.context) filter(where a.active),'') contexts from "+table(container)+" r left join "+link(container)+" a on a."+key(container)+"=r.id group by r.id order by r.id";
        return jdbc.query(sql,Map.of(),(r,n)->new Reference(r.getLong("id"),r.getLong("settings_version"),r.getString("name"),r.getBigDecimal("tare_weight")==null?null:r.getBigDecimal("tare_weight").toPlainString(),r.getBoolean("active"),r.getString("contexts").isEmpty()?Set.of():new TreeSet<>(List.of(r.getString("contexts").split(","))),!container && Set.of("TVs / Monitors / Projectors","Everything Else").contains(r.getString("name")),r.getString("summary_group")));
    }
    public long create(boolean container,ReferenceInput input,String actor) {
        String sql="insert into "+table(container)+" (name,active,settings_updated_by,settings_updated_at"+(container?",tare_weight,summary_group":"")+") values (:name,:active,(select id from app_user where username=:actor),now()"+(container?",:tare,:summaryGroup":"")+") returning id";
        return jdbc.queryForObject(sql,new MapSqlParameterSource().addValue("name",input.name().trim()).addValue("active",input.active()).addValue("tare",input.tareWeight()).addValue("summaryGroup",input.summaryGroup()==null?"OTHER":input.summaryGroup()).addValue("actor",actor),Long.class);
    }
    public int update(boolean container,long id,ReferenceInput input,String actor) {
        return jdbc.update("update "+table(container)+" set name=:name,active=:active,settings_version=settings_version+1,settings_updated_by=(select id from app_user where username=:actor),settings_updated_at=now()"+(container?",tare_weight=:tare,summary_group=:summaryGroup":"")+" where id=:id and settings_version=:version",
                new MapSqlParameterSource().addValue("name",input.name().trim()).addValue("active",input.active()).addValue("tare",input.tareWeight()).addValue("summaryGroup",input.summaryGroup()==null?"OTHER":input.summaryGroup()).addValue("id",id).addValue("version",input.version()).addValue("actor",actor));
    }
    public void applicability(boolean container,long id,Set<String> contexts) {
        jdbc.update("update "+link(container)+" set active=false where "+key(container)+"=:id",Map.of("id",id));
        for(String context:contexts) jdbc.update("insert into "+link(container)+" ("+key(container)+",context,active) values (:id,:context,true) on conflict ("+key(container)+",context) do update set active=true",Map.of("id",id,"context",context));
    }
}
