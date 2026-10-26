package au.org.resourcework.waste_tracking_api.reporting;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReportServiceTests {
    @Test void overviewUsesHobartMidnightsAcrossDaylightSaving() {
        var repository=mock(ReportingRepository.class);
        var clock=Clock.fixed(Instant.parse("2026-10-03T15:30:00Z"),ZoneId.of("Australia/Hobart"));
        when(repository.activity(any())).thenReturn(List.of());
        when(repository.recent(anyInt())).thenReturn(List.of());
        when(repository.trend(any())).thenReturn(List.of());
        when(repository.totals(any())).thenReturn(List.of());
        var overview=new ReportService(repository,clock).overview();
        assertThat(overview.today()).isEqualTo("2026-10-04");
        verify(repository).submitted(Instant.parse("2026-10-03T14:00:00Z"),Instant.parse("2026-10-04T13:00:00Z"));
        assertThat(overview.monthly().filters().from()).isEqualTo(LocalDate.of(2026,10,1));
    }
    @Test void csvTextProtectionLeavesNumericExportToTypedValues() {
        for(String s:List.of("=1+1"," +cmd","-cmd","@SUM(A1)","\tvalue","\nvalue")) assertThat(ReportExportService.safeText(s)).startsWith("'");
        assertThat(ReportExportService.safeText("RWC, caf\u00e9 \"source\"" )).isEqualTo("RWC, caf\u00e9 \"source\"");
    }
    @Test void excelPreservesLargeDecimalAsTextAndOrdinaryWeightsAsNumbers() throws Exception {
        var service=mock(ReportService.class);
        var filter=new ReportFilter(LocalDate.of(2001,1,1),LocalDate.of(2001,1,31),null,null,null,null,null);
        when(service.validate(filter)).thenReturn(filter);
        when(service.summary(filter)).thenReturn(new ReportData.Result(filter,"Australia/Hobart",Instant.EPOCH,List.of(
            new ReportData.Total("PICKUP",null,null,null,null,"Net weight","kg","123456789012345.67"),
            new ReportData.Total("PICKUP",null,null,null,null,"Material weight","kg","12.34"))));
        var file=new ReportExportService(service,Clock.fixed(Instant.EPOCH,ZoneId.of("Australia/Hobart"))).report(filter,"summary","xlsx");
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file.bytes()))) {
            var cells=new java.util.ArrayList<org.apache.poi.ss.usermodel.Cell>();
            for(var row:workbook.getSheet("Summary")) if(row.getCell(5)!=null && row.getCell(5).getCellType()==org.apache.poi.ss.usermodel.CellType.STRING && List.of("Net weight","Material weight").contains(row.getCell(5).getStringCellValue())) cells.add(row.getCell(6));
            assertThat(cells).hasSize(2);
            assertThat(cells.getFirst().getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(cells.getFirst().getStringCellValue()).isEqualTo("123456789012345.67");
            assertThat(cells.getLast().getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(java.math.BigDecimal.valueOf(cells.getLast().getNumericCellValue())).isEqualByComparingTo("12.34");
        }
    }

}
