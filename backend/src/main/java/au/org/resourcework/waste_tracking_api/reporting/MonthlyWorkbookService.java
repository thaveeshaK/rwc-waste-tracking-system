package au.org.resourcework.waste_tracking_api.reporting;

import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static au.org.resourcework.waste_tracking_api.reporting.ReportData.*;

/** Data-only RWC workbook: pivots stored material kg without recalculating or redistributing rounding. */
@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class MonthlyWorkbookService {
    private record Material(String header,String category) {}
    private record Table(String name,List<String> headers,List<List<Object>> rows) {}
    private static final List<Material> INTAKE=List.of(
        new Material("Iron and steel","Iron and Steel"),new Material("Aluminium","Aluminium"),
        new Material("Other non-ferrous","Other Non-Ferrous Metals"),new Material("Mixed metals","Mixed Metals"),
        new Material("Textiles","Textiles"),new Material("Mattresses","Mattresses"),new Material("Timber","Timber"),
        new Material("Bricks","Bricks, Blocks and Pavers"),new Material("Demolition","Building and Demolition Material"),
        new Material("Paper and cardboard","Paper, Cardboard and Books"),new Material("Plastics","Plastics"),
        new Material("Glass","Glass"),new Material("Mixed materials","Mixed Materials"));
    private static final List<Material> UP=List.of(
        new Material("Iron and steel","Iron and Steel"),new Material("Timber","Timber"),new Material("Textiles","Textiles"),
        new Material("Plastics","Plastics"),new Material("Paper and cardboard","Paper, Cardboard and Books"),
        new Material("Mattresses","Mattresses"),new Material("Ewaste","E-waste"),new Material("Glass","Glass"),new Material("Mixed materials","Mixed Materials"));
    private static final List<Material> DOWN=List.of(
        new Material("Iron and steel","Iron and Steel"),new Material("Aluminium","Aluminium"),new Material("Other Non-Ferrous","Other Non-Ferrous Metals"),
        new Material("Mixed Metals","Mixed Metals"),new Material("Ewaste","E-waste"),new Material("Lead Acid Batteries","Lead Acid Batteries"),
        new Material("Timber","Timber"),new Material("Bricks","Bricks, Blocks and Pavers"),new Material("Building and Demolition","Building and Demolition Material"),
        new Material("Whitegoods","Whitegoods / Large Appliances"),new Material("Paper and Cardboard","Paper, Cardboard and Books"),
        new Material("Plastics","Plastics"),new Material("Glass","Glass"),new Material("Mixed Materials","Mixed Materials"));
    private static final List<Material> ELECTRICAL=List.of(new Material("TVs, Monitors, Proj","TVs / Monitors / Projectors"),new Material("Everything Else","Everything Else"));
    private static final List<Material> SCRAP=List.of(new Material("Iron and steel","Iron and Steel"),new Material("Aluminium","Aluminium"),
        new Material("Other non-ferrous","Other Non-Ferrous"),new Material("Mixed metals","Mixed Metals"),new Material("E-waste","E-waste"));
    private final ReportingRepository repository;
    private final ReportService reports;
    public MonthlyWorkbookService(ReportingRepository repository,ReportService reports) {this.repository=repository;this.reports=reports;}

    public ReportExportService.File export(String value) {
        YearMonth month;
        try {if(value==null || !value.matches("[0-9]{4}-[0-9]{2}"))throw new IllegalArgumentException();month=YearMonth.parse(value);}
        catch(RuntimeException e){throw invalid("Use a valid reporting month (YYYY-MM).");}
        if(month.getYear()<1900 || month.getYear()>9998)throw invalid("Excel date exports support reporting months from 1900 through 9998.");
        var filter=reports.validate(new ReportFilter(month.atDay(1),month.atEndOfMonth(),null,null,null,null,null));
        var rows=repository.monthlyWorkbookRows(filter);
        if(rows.size()>100000)throw invalid("Export exceeds 100,000 reporting rows. Use a smaller dataset.");
        var groups=repository.monthlyWorkbookGroups(filter);
        var materials=new HashMap<String,Map<String,BigDecimal>>();
        for(var row:rows)if(row.rowType().equals("MATERIAL"))materials.computeIfAbsent(key(row),k->new HashMap<>()).merge(row.category(),decimal(row.kg()),BigDecimal::add);
        var tables=new ArrayList<Table>();
        tables.add(materialTable("SalvageUpList",rows,"SALVAGE","UP",null,groups,UP,false,true,materials));
        tables.add(materialTable("SalvageDownList",rows,"SALVAGE","DOWN",null,groups,DOWN,false,true,materials));
        tables.add(materialTable("CrateList",rows,"CRATE_PALLET",null,"CRATE",groups,INTAKE,false,false,materials));
        tables.add(materialTable("MiscList",rows,"CRATE_PALLET",null,"OTHER",groups,INTAKE,true,false,materials));
        tables.add(materialTable("PalletList",rows,"CRATE_PALLET",null,"PALLET",groups,INTAKE,false,false,materials));
        tables.add(materialTable("CollTubList",rows,"COLLECTABLES",null,null,groups,INTAKE,false,false,materials));
        var pickup=INTAKE.stream().map(m->m.header().equals("Demolition")?new Material("Building and Demolition",m.category()):m).toList();
        tables.add(materialTable("PickupList",rows,"PICKUP",null,null,groups,pickup,false,false,materials));
        tables.add(materialTable("ElecCrateList",rows,"ELECTRICAL_CRATE_PALLET",null,"CRATE",groups,ELECTRICAL,false,false,materials));
        tables.add(materialTable("ElecPalletList",rows,"ELECTRICAL_CRATE_PALLET",null,"PALLET",groups,ELECTRICAL,false,false,materials));
        var misc=materialTable("ElecMiscList",rows,"ELECTRICAL_CRATE_PALLET",null,"OTHER",groups,ELECTRICAL,true,false,materials);
        if(!misc.rows().isEmpty())tables.add(misc);
        tables.add(new Table("Whitegoods",List.of("Date","Count","Estimated Weight (kg)"),rows.stream()
            .filter(r->r.workflow().equals("WHITEGOODS")).map(r->List.<Object>of(r.date(),r.count(),decimal(r.kg()))).toList()));
        var scrapRows=rows.stream().filter(r->r.workflow().equals("SCRAP")&&r.rowType().equals("MATERIAL")).toList();
        var scrap=new ArrayList<List<Object>>();
        if(!scrapRows.isEmpty()) {
            var totals=new HashMap<String,BigDecimal>();scrapRows.forEach(r->totals.merge(r.category(),decimal(r.kg()),BigDecimal::add));
            var entry=new ArrayList<Object>();entry.add(month.atDay(1));entry.addAll(weights(totals,SCRAP,"Scrap"));scrap.add(entry);
        }
        tables.add(new Table("Scrap",headers(List.of("Month"),SCRAP),scrap));
        tables.add(new Table("Ewaste Pickups",List.of("Date","Organisation","Net Weight (kg)"),rows.stream()
            .filter(r->r.workflow().equals("EWASTE_PICKUP")).map(r->List.<Object>of(r.date(),r.organisation(),decimal(r.kg()))).toList()));
        return render(value,tables);
    }
    private Table materialTable(String name,List<Row> rows,String workflow,String direction,String group,Map<String,String> groups,
            List<Material> columns,boolean container,boolean directionWeight,Map<String,Map<String,BigDecimal>> materials) {
        var prefix=new ArrayList<>(List.of("Date"));if(container)prefix.add("Container Type");if(directionWeight)prefix.add(direction.equals("UP")?"Weight Up":"Weight Down");
        var entries=new ArrayList<List<Object>>();
        for(var row:rows) {
            if(!row.workflow().equals(workflow)||!row.rowType().equals(directionWeight?"DIRECTION":"RECORD")||!Objects.equals(direction,row.direction()))continue;
            if(group!=null && !group.equals(groups.get(row.workflow()+":"+row.id())))continue;
            var entry=new ArrayList<Object>();entry.add(row.date());if(container)entry.add(row.container());if(directionWeight)entry.add(decimal(row.kg()));
            entry.addAll(weights(materials.getOrDefault(key(row),Map.of()),columns,workflow+" #"+row.id()));entries.add(entry);
        }
        return new Table(name,headers(prefix,columns),entries);
    }
    private static String key(Row r){return r.workflow()+":"+r.id()+":"+Objects.toString(r.direction(),"");}
    private static BigDecimal decimal(String value){return value==null?BigDecimal.ZERO:new BigDecimal(value);}
    private static List<String> headers(List<String> prefix,List<Material> columns){var result=new ArrayList<>(prefix);columns.forEach(m->result.add(m.header()));return result;}
    private static List<BigDecimal> weights(Map<String,BigDecimal> saved,List<Material> columns,String record) {
        var supported=columns.stream().map(Material::category).toList();
        for(var e:saved.entrySet())if(e.getValue().signum()!=0&&!supported.contains(e.getKey()))throw invalid("Cannot export "+record+": material '"+e.getKey()+"' has no column in the approved RWC workbook.");
        return columns.stream().map(m->saved.getOrDefault(m.category(),BigDecimal.ZERO)).toList();
    }
    private ReportExportService.File render(String month,List<Table> tables) {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var header=book.createCellStyle();var font=book.createFont();font.setBold(true);header.setFont(font);
            var kg=book.createCellStyle();kg.setDataFormat(book.createDataFormat().getFormat("0.00"));
            var date=book.createCellStyle();date.setDataFormat(book.createDataFormat().getFormat("dd/mm/yyyy"));
            var period=book.createCellStyle();period.setDataFormat(book.createDataFormat().getFormat("mmmm yyyy"));
            for(var table:tables) {
                var sheet=book.createSheet(table.name());var h=sheet.createRow(0);
                for(int i=0;i<table.headers().size();i++){h.createCell(i).setCellValue(table.headers().get(i));h.getCell(i).setCellStyle(header);sheet.setColumnWidth(i,Math.min(32,Math.max(20,table.headers().get(i).length()+2))*256);}
                int index=1;
                for(var values:table.rows()) {
                    var row=sheet.createRow(index++);
                    for(int i=0;i<values.size();i++) {
                        var cell=row.createCell(i);var value=values.get(i);
                        if(value instanceof LocalDate d){cell.setCellValue(d.atStartOfDay());cell.setCellStyle(table.name().equals("Scrap")?period:date);}
                        else if(value instanceof BigDecimal n){cell.setCellValue(n.doubleValue());cell.setCellStyle(kg);}
                        else if(value instanceof Number n)cell.setCellValue(n.doubleValue());
                        else cell.setCellValue(Objects.toString(value,"")); // Literal text, never a formula.
                    }
                }
                sheet.createFreezePane(0,1);sheet.setAutoFilter(new CellRangeAddress(0,Math.max(0,index-1),0,table.headers().size()-1));
            }
            book.write(out);
            return new ReportExportService.File("RWC-Waste-Tracking-"+month+".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
        }catch(IOException e){throw new IllegalStateException("Could not generate monthly workbook.",e);}
    }
    private static ResponseStatusException invalid(String text){return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);}
}
