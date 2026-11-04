package au.org.resourcework.waste_tracking_api.ewastepickup;

import au.org.resourcework.waste_tracking_api.ewastepickup.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ewaste-pickups")
public class EwastePickupController {
    private final EwastePickupService service;
    public EwastePickupController(EwastePickupService service) { this.service = service; }

    @PostMapping("/preview")
    public EwastePickupResponse preview(@Valid @RequestBody EwastePickupRequest input, @RequestParam(required = false) Long recordId) {
        return recordId == null ? service.preview(input) : service.previewEdit(recordId, input);
    }
    @PostMapping
    public ResponseEntity<EwastePickupResponse> submit(@Valid @RequestBody EwastePickupRequest input, Principal principal) {
        var result = service.submit(input, principal.getName());
        return result.created() ? ResponseEntity.created(URI.create("/api/ewaste-pickups/" + result.record().id())).body(result.record())
                : ResponseEntity.ok(result.record());
    }
    @GetMapping
    public List<EwastePickupResponse> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return service.list(from, to);
    }
    @GetMapping("/organisations")
    public List<String> organisations(@RequestParam(defaultValue="") String query) { return service.organisations(query); }
    @GetMapping("/{id}")
    public EwastePickupResponse get(@PathVariable Long id) { return service.get(id); }
    @PutMapping("/{id}")
    public EwastePickupResponse update(@PathVariable Long id, @Valid @RequestBody EwastePickupRequest input, Principal principal) {
        return service.update(id, input, principal.getName());
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
