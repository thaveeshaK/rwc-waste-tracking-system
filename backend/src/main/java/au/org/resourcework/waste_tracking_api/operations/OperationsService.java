package au.org.resourcework.waste_tracking_api.operations;

import java.util.List;
import au.org.resourcework.waste_tracking_api.reporting.ReportService;
import au.org.resourcework.waste_tracking_api.reporting.ReportData.TodayActivitySummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
@PreAuthorize("hasAnyRole('STAFF','ADMIN')")
public class OperationsService {
    private final OperationsRepository repository;
    private final ReportService reports;
    public OperationsService(OperationsRepository repository, ReportService reports) { this.repository=repository;this.reports=reports; }
    public List<RecentSubmission> recent() { return repository.recent(); }
    public TodayActivitySummary todayActivity() { return reports.todayActivity(); }
}
