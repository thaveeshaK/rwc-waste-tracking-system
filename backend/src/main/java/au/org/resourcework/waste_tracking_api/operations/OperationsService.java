package au.org.resourcework.waste_tracking_api.operations;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
@PreAuthorize("hasAnyRole('STAFF','ADMIN')")
public class OperationsService {
    private final OperationsRepository repository;
    public OperationsService(OperationsRepository repository) { this.repository=repository; }
    public List<RecentSubmission> recent() { return repository.recent(); }
}
