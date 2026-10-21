package au.org.resourcework.waste_tracking_api.reference;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReferenceController {
    private final ReferenceService service;

    public ReferenceController(ReferenceService service) { this.service = service; }

    @GetMapping("/container-types")
    public List<ReferenceService.ContainerResponse> containers() { return service.containers(); }

    @GetMapping("/material-categories")
    public List<ReferenceService.MaterialResponse> materials() { return service.materials(); }
}
