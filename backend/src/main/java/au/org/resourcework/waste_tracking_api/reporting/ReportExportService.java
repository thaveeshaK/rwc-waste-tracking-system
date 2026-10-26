package au.org.resourcework.waste_tracking_api.reporting;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static au.org.resourcework.waste_tracking_api.reporting.ReportData.*;

@Service
@Transactional(readOnly=true)
@PreAuthorize("hasRole('ADMIN')")
public class ReportExportService {
    public record File(String filename, String contentType, byte[] bytes) {}
    private record Table(String name, List<String> headers, List<List<Object>> rows) {}
    private final ReportService reports;
    private final java.time.Clock clock;
    public ReportExportService(ReportService reports, java.time.Clock clock) { this.reports=reports; this.clock=clock; }

    public File report(ReportFilter input, String type, String format) {
        if (!Set.of("summary","details","electrical-testing").contains(type)) bad("Unknown export report type.");
        var f=reports.validate(input);
        if (type.equals("electrical-testing") && !"ELECTRICAL_TESTING".equals(f.workflow())) bad("Select Electrical Testing before exporting its KPI report.");
        var tables=new ArrayList<Table>();
        if (type.equals("summary") || type.equals("electrical-testing")) tables.add(totalTable("Summary",reports.summary(f).totals()));
        if (type.equals("details") || type.equals("electrical-testing")) {
            var rows=reports.exportRows(f);
            if (format.equals("csv")) tables.add(detailTable("Detailed Records",rows));
            else {
                addRows(tables,"Detailed Records",rows,r->r.rowType().equals("RECORD") && !Set.of("WHITEGOODS","ELECTRICAL_TESTING").contains(r.workflow()));
                addRows(tables,"Material Breakdown",rows,r->Set.of("MATERIAL","DIRECTION").contains(r.rowType()));
                addRows(tables,"Whitegoods",rows,r->r.workflow().equals("WHITEGOODS"));
                addRows(tables,"Electrical Testing KPI",rows,r->r.workflow().equals("ELECTRICAL_TESTING") && !r.rowType().equals("REFERENCE"));
                addRows(tables,"Sales References",rows,r->r.rowType().equals("REFERENCE"));
            }
        }
        // A CSV has one rectangular schema; KPI CSV uses detailed tally/reference rows.
        if (format.equals("csv") && type.equals("electrical-testing")) tables.removeFirst();
        if (tables.isEmpty()) tables.add(detailTable("Detailed Records",List.of()));
        var metadata=metadata(f);
        metadata.put("Report",type);
        return render("report-"+type+"-"+f.from()+"-to-"+f.to(),format,tables,metadata);
    }
    public File monthly(String month,String format) {
        var summary=reports.monthly(month);
        var metadata=new LinkedHashMap<String,String>();
        metadata.put("Reporting month",month); metadata.put("Timezone",summary.timezone());
        metadata.put("Generated at",summary.generatedAt().toString()); metadata.put("Note",summary.note());
        return render("waste-levy-summary-"+month,format,List.of(totalTable("Monthly Summary",summary.totals())),metadata);
    }
    private Map<String,String> metadata(ReportFilter f) {
        var m=new LinkedHashMap<String,String>();
        m.put("Generated at",clock.instant().toString()); m.put("From",f.from().toString()); m.put("To",f.to().toString()); m.put("Timezone","Australia/Hobart");
        m.put("Workflow",Objects.toString(f.workflow(),"All")); m.put("Material category",Objects.toString(f.materialCategoryId(),"All"));
        m.put("Location contains",Objects.toString(f.location(),"")); m.put("Direction",Objects.toString(f.direction(),"All"));
        m.put("Testing category",Objects.toString(f.testingCategoryId(),"All"));
        m.put("Note","Active records only. Weights in kg; category rows and parent weights are separate. Reference dates denote reporting months. Operational totals are not certified diversion figures.");
        return m;
    }
    private void addRows(List<Table> tables,String name,List<Row> rows,Function<Row,Boolean> predicate) {
        var selected=rows.stream().filter(predicate::apply).toList();
        if (!selected.isEmpty()) tables.add(detailTable(name,selected));
    }
    private Table totalTable(String name,List<Total> totals) {
        var rows=new ArrayList<List<Object>>();
        for(var t:totals) rows.add(Arrays.asList(t.workflow(),t.direction(),t.container(),t.categoryId(),t.category(),t.metric(),new BigDecimal(t.value()),t.unit()));
        return new Table(name,List.of("workflow","direction","container","category_id","category","metric","value","unit"),rows);
    }
    private Table detailTable(String name,List<Row> rows) {
        var values=new ArrayList<List<Object>>();
        for(var r:rows) values.add(Arrays.asList(r.rowType(),r.workflow(),r.id(),r.date().toString(),r.container(),r.location(),r.crew(),r.direction(),r.categoryId(),r.category(),r.status(),
                decimal(r.kg()),decimal(r.grossKg()),decimal(r.tareKg()),decimal(r.percentage()),r.count(),decimal(r.factor()),r.timeUp(),r.timeDown(),r.submissionId(),r.version(),r.createdBy(),r.createdAt().toString(),r.updatedBy(),r.updatedAt().toString()));
        return new Table(name,List.of("row_type","workflow","id","date_or_reporting_month","container","location","crew","direction","category_id","category","status",
                "kg","gross_kg","tare_kg","percentage","count","kg_per_item","time_up","time_down","submission_id","version","created_by","created_at","updated_by","updated_at"),values);
    }
    private static BigDecimal decimal(String s) { return s==null?null:new BigDecimal(s); }
    private File render(String filename,String format,List<Table> tables,Map<String,String> metadata) {
        if (format.equals("csv")) return new File(filename+".csv","text/csv;charset=UTF-8",csv(tables.getFirst(),metadata));
        if (!format.equals("xlsx")) bad("Choose csv or xlsx export format.");
        try(var book=new XSSFWorkbook(); var out=new ByteArrayOutputStream()) {
            var header=book.createCellStyle(); var font=book.createFont(); font.setBold(true); header.setFont(font);
            var numeric=book.createCellStyle(); numeric.setDataFormat(book.createDataFormat().getFormat("0.00"));
            for(var table:tables) {
                var sheet=book.createSheet(table.name()); int index=0;
                for(var e:metadata.entrySet()) { var row=sheet.createRow(index++); row.createCell(0).setCellValue(e.getKey()); row.createCell(1).setCellValue(e.getValue()); }
                index++; int headerIndex=index; var h=sheet.createRow(index++);
                for(int i=0;i<table.headers().size();i++) { h.createCell(i).setCellValue(table.headers().get(i)); h.getCell(i).setCellStyle(header); sheet.setColumnWidth(i,22*256); }
                for(var values:table.rows()) { var row=sheet.createRow(index++); for(int i=0;i<values.size();i++) writeCell(row.createCell(i),values.get(i),numeric); }
                sheet.createFreezePane(0,headerIndex+1);
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(headerIndex,Math.max(headerIndex,index-1),0,table.headers().size()-1));
            }
            book.write(out);
            return new File(filename+".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
        } catch(IOException e) { throw new IllegalStateException("Could not generate Excel export.",e); }
    }
    private static void writeCell(Cell cell,Object value,CellStyle numeric) {
        if(value==null) return;
        if(value instanceof Number n) {
            BigDecimal exact=new BigDecimal(n.toString());
            // Excel's numeric cells cannot preserve arbitrary decimal precision; use text when necessary.
            if(exact.precision()<=15 && BigDecimal.valueOf(exact.doubleValue()).compareTo(exact)==0) {
                cell.setCellValue(exact.doubleValue()); if(value instanceof BigDecimal) cell.setCellStyle(numeric);
            } else cell.setCellValue(exact.toPlainString());
        } else cell.setCellValue(value.toString()); // Never create a formula from source text.
    }
    private static byte[] csv(Table table,Map<String,String> metadata) {
        var out=new StringBuilder("\uFEFF");
        var headers=new ArrayList<Object>(metadata.keySet()); headers.addAll(table.headers()); appendCsv(out,headers);
        for(var row:table.rows()) { var values=new ArrayList<Object>(metadata.values()); values.addAll(row); appendCsv(out,values); }
        // Retain headers and report context even for an empty result.
        if(table.rows().isEmpty()) { var values=new ArrayList<Object>(metadata.values()); for(int i=0;i<table.headers().size();i++) values.add(null); appendCsv(out,values); }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void appendCsv(StringBuilder out,List<?> values) {
        for(int i=0;i<values.size();i++) {
            if(i>0) out.append(','); Object value=values.get(i);
            String text=value==null?"":value.toString();
            if(!(value instanceof Number)) text=safeText(text);
            out.append('"').append(text.replace("\"","\"\"")).append('"');
        }
        out.append("\r\n");
    }
    static String safeText(String text) {
        String stripped=text.stripLeading();
        if(!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0 || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) return "'"+text;
        return text;
    }
    private static void bad(String s) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,s); }
}
