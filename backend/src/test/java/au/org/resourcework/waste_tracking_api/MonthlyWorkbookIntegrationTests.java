package au.org.resourcework.waste_tracking_api;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"debug=false","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO"})
@Transactional
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class MonthlyWorkbookIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    long actor,container;
    static final String URL="/api/admin/waste-levy-summary/workbook";
    static final List<String> CORE=List.of("SalvageUpList","SalvageDownList","CrateList","MiscList","PalletList","CollTubList","PickupList","ElecCrateList","ElecPalletList","Whitegoods","Scrap","Ewaste Pickups");
    static final List<String> INTAKE=List.of("Date","Iron and steel","Aluminium","Other non-ferrous","Mixed metals","Textiles","Mattresses","Timber","Bricks","Demolition","Paper and cardboard","Plastics","Glass","Mixed materials");
    @BeforeEach void setup() throws Exception {
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings());}
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        actor=jdbc.queryForObject("select min(id) from app_user where role='ADMIN'",Long.class);
        container=jdbc.queryForObject("select min(id) from container_type",Long.class);
    }
    private byte[] download(String month)throws Exception {
        return mvc.perform(get(URL).with(user("admin").roles("ADMIN")).param("month",month)).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition","attachment; filename=\"RWC-Waste-Tracking-"+month+".xlsx\""))
            .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
    }
    @Test void adminOnlyEndpointAndMonthValidation()throws Exception {
        mvc.perform(get(URL).param("month","2003-04")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).param("month","2003-04").with(user("staff").roles("STAFF"))).andExpect(status().isForbidden());
        for(String value:List.of("bad","2003-13","2003-4","0000-01","1899-12","9999-01"))mvc.perform(get(URL).param("month",value).with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }
    @Test void emptyMonthHasExactlyTwelveVisibleHeaderOnlySheetsWithExactHeaders()throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download("1951-02")))) {
            assertThat(names(book)).isEqualTo(CORE);assertHeaders(book);
            for(var sheet:book) {
                assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(1);
                assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
                assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)1);
                assertThat(book.isSheetHidden(book.getSheetIndex(sheet))).isFalse();
                assertThat(book.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
                assertThat(((org.apache.poi.xssf.usermodel.XSSFSheet)sheet).getCTWorksheet().isSetAutoFilter()).isTrue();
            }
        }
    }
    @Test void representativeWorkbookUsesStoredSnapshotsAndExactRoutingAndNumericData()throws Exception {
        for(String group:List.of("CRATE","OTHER","PALLET")){load(false,group,"2003-04-12",false,false);load(true,group,"2003-04-12",false,false);}
        long salvage=jdbc.queryForObject("insert into salvage_record(submission_id,record_date,crew,time_up,kg_up,time_down,kg_down,created_by,updated_by,created_at,updated_at) values (?,date '2003-04-10','Crew',time '09:00',10,time '10:00',20,?,?,now(),now()) returning id",Long.class,UUID.randomUUID(),actor,actor);
        material("salvage",salvage,"Iron and Steel","60","6","UP");material("salvage",salvage,"Timber","40","4","UP");material("salvage",salvage,"Iron and Steel","25","5","DOWN");material("salvage",salvage,"Aluminium","75","15","DOWN");
        long tub=jdbc.queryForObject("insert into collectable_record(submission_id,record_date,gross_weight,tare_weight,net_weight,created_by,updated_by,created_at,updated_at) values (?,date '2003-04-11',12,2,10,?,?,now(),now()) returning id",Long.class,UUID.randomUUID(),actor,actor);
        material("collectable",tub,"Iron and Steel","100","10",null);
        long pickup=jdbc.queryForObject("insert into pickup_record(submission_id,record_date,pickup_location,net_weight,created_by,updated_by,created_at,updated_at) values (?,date '2003-04-13','Source',9,?,?,now(),now()) returning id",Long.class,UUID.randomUUID(),actor,actor);
        material("pickup",pickup,"Iron and Steel","33.33","3",null);material("pickup",pickup,"Timber","66.67","6",null);
        jdbc.update("insert into whitegoods_record(submission_id,record_date,whitegoods_count,kg_per_item,estimated_weight,created_by,updated_by,created_at,updated_at) values (?,date '2003-04-14',2,48,96,?,?,now(),now()),(?,date '2003-04-15',3,52.25,156.75,?,?,now(),now())",UUID.randomUUID(),actor,actor,UUID.randomUUID(),actor,actor);
        scrap("2003-04-01",false);scrap("2003-04-01",false);scrap("2003-03-01",false);scrap("2003-05-01",false);scrap("2003-04-01",true);
        ewaste("2003-04-16","=2+2",false);ewaste("2003-04-17","<img src=x onerror=alert(1)>",false);ewaste("2003-03-31","Excluded",false);ewaste("2003-05-01","Excluded",false);ewaste("2003-04-18","Deleted",true);
        for(boolean electrical:List.of(false,true)) {
            load(electrical,"CRATE","2003-03-31",false,false);load(electrical,"PALLET","2003-05-01",false,false);
            load(electrical,"OTHER","2003-04-12",true,false);load(electrical,"OTHER","2003-04-12",false,true);
        }
        // Renames/group/tare/settings changes must never affect export routing or saved weights.
        jdbc.update("update container_type set name='Current renamed container',summary_group='OTHER',tare_weight=99 where id=?",container);
        jdbc.update("update application_settings set collectables_tub_tare=9,whitegoods_kg_per_item=60 where id=1");
        byte[] bytes=download("2003-04");Files.createDirectories(Path.of("target"));Files.write(Path.of("target/monthly-workbook-representative.xlsx"),bytes);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var expected=new ArrayList<>(CORE);expected.add(9,"ElecMiscList");assertThat(names(book)).isEqualTo(expected);assertHeaders(book);
            for(String name:List.of("CrateList","MiscList","PalletList","CollTubList","PickupList","ElecCrateList","ElecPalletList","ElecMiscList","SalvageUpList","SalvageDownList"))assertThat(book.getSheet(name).getLastRowNum()).as(name).isEqualTo(1);
            for(String name:List.of("CrateList","PalletList")){number(book,name,1,1,"2.50");number(book,name,1,7,"7.50");sum(book,name,1,1,"10.00");}
            assertThat(book.getSheet("MiscList").getRow(1).getCell(1).getStringCellValue()).isEqualTo("Historical OTHER");sum(book,"MiscList",1,2,"10.00");
            for(String name:List.of("ElecCrateList","ElecPalletList")){number(book,name,1,1,"3.50");number(book,name,1,2,"6.50");sum(book,name,1,1,"10.00");}
            assertThat(book.getSheet("ElecMiscList").getRow(1).getCell(1).getStringCellValue()).isEqualTo("Historical OTHER");sum(book,"ElecMiscList",1,2,"10.00");
            number(book,"SalvageUpList",1,1,"10");sum(book,"SalvageUpList",1,2,"10");number(book,"SalvageUpList",1,2,"6");number(book,"SalvageUpList",1,3,"4");
            number(book,"SalvageDownList",1,1,"20");sum(book,"SalvageDownList",1,2,"20");number(book,"SalvageDownList",1,2,"5");number(book,"SalvageDownList",1,3,"15");
            sum(book,"CollTubList",1,1,"10");sum(book,"PickupList",1,1,"9");number(book,"PickupList",1,1,"3");number(book,"PickupList",1,7,"6");
            assertThat(book.getSheet("Whitegoods").getLastRowNum()).isEqualTo(2);number(book,"Whitegoods",1,1,"2");number(book,"Whitegoods",1,2,"96");number(book,"Whitegoods",2,1,"3");number(book,"Whitegoods",2,2,"156.75");
            assertThat(book.getSheet("Scrap").getLastRowNum()).isEqualTo(1);for(int col=1;col<=5;col++)number(book,"Scrap",1,col,""+(2*col));
            assertThat(book.getSheet("Ewaste Pickups").getLastRowNum()).isEqualTo(2);assertThat(book.getSheet("Ewaste Pickups").getRow(1).getCell(1).getStringCellValue()).isEqualTo("=2+2");number(book,"Ewaste Pickups",1,2,"17.25");
            for(var sheet:book) {
                LocalDate previous=null;
                for(int row=1;row<=sheet.getLastRowNum();row++) {
                    var date=sheet.getRow(row).getCell(0);assertThat(date.getCellType()).isEqualTo(CellType.NUMERIC);assertThat(DateUtil.isCellDateFormatted(date)).isTrue();
                    LocalDate actual=date.getLocalDateTimeCellValue().toLocalDate();assertThat(YearMonth.from(actual)).isEqualTo(YearMonth.of(2003,4));if(previous!=null)assertThat(actual).isAfterOrEqualTo(previous);previous=actual;
                    for(var cell:sheet.getRow(row)) {
                        assertThat(cell.getCellType()).isNotIn(CellType.FORMULA,CellType.ERROR);
                        boolean textColumn=cell.getColumnIndex()==1&&Set.of("MiscList","ElecMiscList","Ewaste Pickups").contains(sheet.getSheetName());
                        if(cell.getColumnIndex()>0&&!textColumn)assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
                    }
                }
            }
        }
    }
    @Test void storedRoundingIsNotRedistributedAndUnknownMaterialsAreNotSilentlyLost()throws Exception {
        long id=load(false,"CRATE","2004-04-01",false,false);
        jdbc.update("delete from crate_pallet_material where crate_pallet_record_id=?",id);
        jdbc.update("update crate_pallet_record set gross_weight=tare_weight+0.01,net_weight=0.01 where id=?",id);
        material("crate_pallet",id,"Iron and Steel","50","0.01",null);material("crate_pallet",id,"Timber","50","0.01",null);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download("2004-04")))){sum(book,"CrateList",1,1,"0.02");}
        long category=jdbc.queryForObject("insert into material_category(name) values ('Workbook unmapped category') returning id",Long.class);
        jdbc.update("update crate_pallet_material set material_category_id=? where crate_pallet_record_id=? and material_category_id=(select id from material_category where name='Timber')",category,id);
        mvc.perform(get(URL).param("month","2004-04").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }
    @Test void adjacentMonthsAndDeletedRowsAreExcludedForEveryWorkflow() throws Exception {
        for(String date:List.of("2007-03-31","2007-05-01","2007-04-01")) {
            boolean deleted=date.equals("2007-04-01");
            for(boolean electrical:List.of(false,true))load(electrical,"OTHER",date,false,deleted);
            for(String table:List.of("collectable_record","pickup_record","salvage_record","whitegoods_record")) {
                String columns=switch(table) {
                    case "collectable_record"->"gross_weight,tare_weight,net_weight";
                    case "pickup_record"->"pickup_location,net_weight";
                    case "salvage_record"->"crew,time_up,kg_up,time_down,kg_down";
                    default->"whitegoods_count,kg_per_item,estimated_weight";
                };
                String values=switch(table) {
                    case "collectable_record"->"12,2,10";
                    case "pickup_record"->"'Source',10";
                    case "salvage_record"->"'Crew',time '09:00',1,time '10:00',2";
                    default->"1,48,48";
                };
                jdbc.update("insert into "+table+"(submission_id,record_date,"+columns+",created_by,updated_by,created_at,updated_at,deleted_by,deleted_at) values (?, ?,"+values+",?,?,now(),now(),?,?)",UUID.randomUUID(),LocalDate.parse(date),actor,actor,deleted?actor:null,deleted?java.sql.Timestamp.from(Instant.now()):null);
            }
            ewaste(date,"Excluded",deleted);scrap(date.substring(0,7)+"-01",deleted);
        }
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download("2007-04")))) {
            assertThat(names(book)).isEqualTo(CORE);
            for(var sheet:book)assertThat(sheet.getLastRowNum()).as(sheet.getSheetName()).isZero();
        }
    }
    private long load(boolean electrical,String group,String date,boolean incomplete,boolean deleted) {
        String table=electrical?"electrical_crate_pallet":"crate_pallet";
        jdbc.update("update container_type set name=?,summary_group=? where id=?","Historical "+group,group,container);
        long id=jdbc.queryForObject("insert into "+table+"_record(submission_id,record_date,container_type_id,container_name,summary_group_snapshot,gross_weight,tare_weight,net_weight,status,created_by,updated_by,created_at,updated_at) values (?,?,?, ?,?,14,4,10,?,?,?,now(),now()) returning id",Long.class,UUID.randomUUID(),LocalDate.parse(date),container,"Historical "+group,group,incomplete?"INCOMPLETE":"COMPLETED",actor,actor);
        if(deleted)jdbc.update("update "+table+"_record set deleted_at=now(),deleted_by=? where id=?",actor,id);
        if(!incomplete){material(table,id,electrical?"TVs / Monitors / Projectors":"Iron and Steel",electrical?"35":"25",electrical?"3.50":"2.50",null);material(table,id,electrical?"Everything Else":"Timber",electrical?"65":"75",electrical?"6.50":"7.50",null);}
        return id;
    }
    private void material(String table,long record,String name,String percent,String kg,String direction) {
        long cat=jdbc.queryForObject("select id from material_category where name=?",Long.class,name);
        if(direction==null)jdbc.update("insert into "+table+"_material("+table+"_record_id,material_category_id,percentage,material_weight) values (?,?,?,?)",record,cat,new BigDecimal(percent),new BigDecimal(kg));
        else jdbc.update("insert into salvage_material(salvage_record_id,direction,material_category_id,percentage,material_weight) values (?,?,?,?,?)",record,direction,cat,new BigDecimal(percent),new BigDecimal(kg));
    }
    private void scrap(String month,boolean deleted) {jdbc.update("insert into scrap_record(submission_id,reporting_month,iron_and_steel_weight,aluminium_weight,other_non_ferrous_weight,mixed_metals_weight,ewaste_weight,created_by,updated_by,created_at,updated_at,deleted_by,deleted_at) values (?,?,1,2,3,4,5,?,?,now(),now(),?,?)",UUID.randomUUID(),LocalDate.parse(month),actor,actor,deleted?actor:null,deleted?java.sql.Timestamp.from(Instant.now()):null);}
    private void ewaste(String date,String name,boolean deleted) {jdbc.update("insert into ewaste_pickup_record(submission_id,record_date,organisation,entered_weight,total_tare_weight,net_weight,created_by,updated_by,created_at,updated_at,deleted_by,deleted_at) values (?,?,?,20,2.75,17.25,?,?,now(),now(),?,?)",UUID.randomUUID(),LocalDate.parse(date),name,actor,actor,deleted?actor:null,deleted?java.sql.Timestamp.from(Instant.now()):null);}
    private List<String> names(XSSFWorkbook b){var result=new ArrayList<String>();for(var s:b)result.add(s.getSheetName());return result;}
    private void number(XSSFWorkbook b,String sheet,int row,int col,String expected){var cell=b.getSheet(sheet).getRow(row).getCell(col);assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);assertThat(BigDecimal.valueOf(cell.getNumericCellValue())).isEqualByComparingTo(expected);}
    private void sum(XSSFWorkbook b,String sheet,int row,int from,String expected){BigDecimal total=BigDecimal.ZERO;for(int col=from;col<b.getSheet(sheet).getRow(row).getLastCellNum();col++)total=total.add(BigDecimal.valueOf(b.getSheet(sheet).getRow(row).getCell(col).getNumericCellValue()));assertThat(total).isEqualByComparingTo(expected);}
    private void assertHeaders(XSSFWorkbook b) {
        var headers=new LinkedHashMap<String,List<String>>();headers.put("SalvageUpList",List.of("Date","Weight Up","Iron and steel","Timber","Textiles","Plastics","Paper and cardboard","Mattresses","Ewaste","Glass","Mixed materials"));
        headers.put("SalvageDownList",List.of("Date","Weight Down","Iron and steel","Aluminium","Other Non-Ferrous","Mixed Metals","Ewaste","Lead Acid Batteries","Timber","Bricks","Building and Demolition","Whitegoods","Paper and Cardboard","Plastics","Glass","Mixed Materials"));
        for(String name:List.of("CrateList","PalletList","CollTubList"))headers.put(name,INTAKE);
        var misc=new ArrayList<>(INTAKE);misc.add(1,"Container Type");headers.put("MiscList",misc);
        var pickup=new ArrayList<>(INTAKE);pickup.set(9,"Building and Demolition");headers.put("PickupList",pickup);
        for(String name:List.of("ElecCrateList","ElecPalletList"))headers.put(name,List.of("Date","TVs, Monitors, Proj","Everything Else"));
        if(b.getSheet("ElecMiscList")!=null)headers.put("ElecMiscList",List.of("Date","Container Type","TVs, Monitors, Proj","Everything Else"));
        headers.put("Whitegoods",List.of("Date","Count","Estimated Weight (kg)"));headers.put("Scrap",List.of("Month","Iron and steel","Aluminium","Other non-ferrous","Mixed metals","E-waste"));headers.put("Ewaste Pickups",List.of("Date","Organisation","Net Weight (kg)"));
        headers.forEach((name,expected)->{var actual=new ArrayList<String>();for(var cell:b.getSheet(name).getRow(0))actual.add(cell.getStringCellValue());assertThat(actual).as(name).isEqualTo(expected);});
    }
}
