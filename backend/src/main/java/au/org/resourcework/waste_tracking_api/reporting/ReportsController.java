package au.org.resourcework.waste_tracking_api.reporting;

import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static au.org.resourcework.waste_tracking_api.reporting.ReportData.*;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class ReportsController {
    private final ReportService reports;
    public ReportsController(ReportService reports) { this.reports=reports; }
    @GetMapping("/overview") public Overview overview() { return reports.overview(); }
    @GetMapping("/reports/options") public Options options() { return reports.options(); }
    @GetMapping("/reports/summary") public Result summary(@ModelAttribute ReportFilter input) { return reports.summary(input); }
    @GetMapping("/reports/records")
    public Details details(@ModelAttribute ReportFilter input, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size) { return reports.details(input,page,size); }
    @GetMapping("/reports/electrical-testing")
    public Details testing(@ModelAttribute ReportFilter input, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return reports.details(new ReportFilter(input.from(),input.to(),"ELECTRICAL_TESTING",null,null,null,input.testingCategoryId()),page,size);
    }
    @GetMapping("/waste-levy-summary") public Monthly monthly(@RequestParam String month) { return reports.monthly(month); }
}
