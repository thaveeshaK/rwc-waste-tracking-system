package au.org.resourcework.waste_tracking_api.salvage;

import au.org.resourcework.waste_tracking_api.salvage.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/salvage-records")
public class SalvageController {
    private final SalvageService service;
    public SalvageController(SalvageService service) { this.service = service; }

    @PostMapping("/preview")
    public SalvageResponse preview(@Valid @RequestBody SalvageRequest input, @RequestParam(required = false) Long recordId) {
        return recordId == null ? service.preview(input) : service.previewEdit(recordId, input);
    }
    @PostMapping
    public ResponseEntity<SalvageResponse> submit(@Valid @RequestBody SalvageRequest input, Principal principal) {
        var result = service.submit(input, principal.getName());
        return result.created() ? ResponseEntity.created(URI.create("/api/salvage-records/" + result.record().id())).body(result.record())
                : ResponseEntity.ok(result.record());
    }
    @GetMapping
    public List<SalvageResponse> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return service.list(from, to);
    }
    @GetMapping("/{id}")
    public SalvageResponse get(@PathVariable Long id) { return service.get(id); }
    @PutMapping("/{id}")
    public SalvageResponse update(@PathVariable Long id, @Valid @RequestBody SalvageRequest input, Principal principal) {
        return service.update(id, input, principal.getName());
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
