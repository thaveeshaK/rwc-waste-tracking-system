package au.org.resourcework.waste_tracking_api.scrap;

import au.org.resourcework.waste_tracking_api.scrap.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scrap-records")
public class ScrapController {
    private final ScrapService service;
    public ScrapController(ScrapService service) { this.service = service; }

    @PostMapping("/preview")
    public ScrapPreview preview(@Valid @RequestBody ScrapRequest input, @RequestParam(required = false) Long recordId) {
        return recordId == null ? service.preview(input) : service.previewEdit(recordId, input);
    }
    @PostMapping
    public ResponseEntity<ScrapResponse> submit(@Valid @RequestBody ScrapRequest input, Principal principal) {
        var result = service.submit(input, principal.getName());
        return result.created() ? ResponseEntity.created(URI.create("/api/scrap-records/" + result.record().id())).body(result.record())
                : ResponseEntity.ok(result.record());
    }
    @GetMapping
    public List<ScrapResponse> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return service.list(from, to);
    }
    @GetMapping("/monthly-summary")
    public ScrapTotals monthlySummary(@RequestParam String month) { return service.monthlySummary(month); }
    @GetMapping("/{id}")
    public ScrapResponse get(@PathVariable Long id) { return service.get(id); }
    @PutMapping("/{id}")
    public ScrapResponse update(@PathVariable Long id, @Valid @RequestBody ScrapRequest input, Principal principal) {
        return service.update(id, input, principal.getName());
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
