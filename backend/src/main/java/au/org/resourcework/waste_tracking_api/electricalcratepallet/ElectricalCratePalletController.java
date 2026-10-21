package au.org.resourcework.waste_tracking_api.electricalcratepallet;

import au.org.resourcework.waste_tracking_api.electricalcratepallet.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/electrical-crate-pallet-records")
public class ElectricalCratePalletController {
    private final ElectricalCratePalletService service;

    public ElectricalCratePalletController(ElectricalCratePalletService service) { this.service = service; }

    @PostMapping("/preview")
    public ElectricalCratePalletResponse preview(@Valid @RequestBody ElectricalCratePalletRequest input,
            @RequestParam(required = false) Long recordId) {
        return recordId == null ? service.preview(input) : service.previewEdit(recordId, input);
    }

    @PostMapping
    public ResponseEntity<ElectricalCratePalletResponse> submit(@Valid @RequestBody ElectricalCratePalletRequest input, Principal principal) {
        var result = service.submit(input, principal.getName());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/electrical-crate-pallet-records/" + result.record().id())).body(result.record())
                : ResponseEntity.ok(result.record());
    }

    @GetMapping
    public List<ElectricalCratePalletResponse> list(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) { return service.list(from, to); }

    @GetMapping("/{id}")
    public ElectricalCratePalletResponse get(@PathVariable Long id) { return service.get(id); }

    @PutMapping("/{id}")
    public ElectricalCratePalletResponse update(@PathVariable Long id, @Valid @RequestBody ElectricalCratePalletRequest input,
            Principal principal) { return service.update(id, input, principal.getName()); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
