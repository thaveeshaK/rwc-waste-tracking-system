package au.org.resourcework.waste_tracking_api.reporting;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class ReportExportController {
    private final ReportExportService exports;
    public ReportExportController(ReportExportService exports) { this.exports=exports; }
    @GetMapping("/reports/export")
    public ResponseEntity<byte[]> report(@ModelAttribute ReportFilter filters, @RequestParam(defaultValue="summary") String report,
            @RequestParam(defaultValue="csv") String format) { return response(exports.report(filters,report,format)); }
    @GetMapping("/waste-levy-summary/export")
    public ResponseEntity<byte[]> monthly(@RequestParam String month,@RequestParam(defaultValue="csv") String format) { return response(exports.monthly(month,format)); }
    private ResponseEntity<byte[]> response(ReportExportService.File file) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.filename()+"\"")
                .cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.contentType())).body(file.bytes());
    }
}
