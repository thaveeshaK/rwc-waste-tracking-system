package au.org.resourcework.waste_tracking_api.pickup;

import au.org.resourcework.waste_tracking_api.pickup.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pickup-records")
public class PickupController {
    private final PickupService service;
    public PickupController(PickupService service) { this.service = service; }

    @PostMapping("/preview")
    public PickupResponse preview(@Valid @RequestBody PickupRequest input, @RequestParam(required = false) Long recordId) {
        return recordId == null ? service.preview(input) : service.previewEdit(recordId, input);
    }
    @PostMapping
    public ResponseEntity<PickupResponse> submit(@Valid @RequestBody PickupRequest input, Principal principal) {
        var result = service.submit(input, principal.getName());
        return result.created() ? ResponseEntity.created(URI.create("/api/pickup-records/" + result.record().id())).body(result.record())
                : ResponseEntity.ok(result.record());
    }
    @GetMapping
    public List<PickupResponse> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return service.list(from, to);
    }
    @GetMapping("/{id}")
    public PickupResponse get(@PathVariable Long id) { return service.get(id); }
    @PutMapping("/{id}")
    public PickupResponse update(@PathVariable Long id, @Valid @RequestBody PickupRequest input, Principal principal) {
        return service.update(id, input, principal.getName());
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
