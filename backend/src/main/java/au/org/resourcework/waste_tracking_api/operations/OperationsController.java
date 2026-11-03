package au.org.resourcework.waste_tracking_api.operations;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final OperationsService service;
    public OperationsController(OperationsService service) { this.service=service; }
    @GetMapping("/recent-submissions")
    public List<RecentSubmission> recent() { return service.recent(); }
}
