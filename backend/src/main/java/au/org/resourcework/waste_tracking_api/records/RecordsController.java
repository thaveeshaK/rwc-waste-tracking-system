package au.org.resourcework.waste_tracking_api.records;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/records")
public class RecordsController {
    private final RecordsService service;
    public RecordsController(RecordsService service) { this.service=service; }
    @GetMapping
    public PageResult<RecordsService.Item> list(@RequestParam(required=false) String workflow,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size) {
        return service.list(workflow,from,to,page,size);
    }
}
