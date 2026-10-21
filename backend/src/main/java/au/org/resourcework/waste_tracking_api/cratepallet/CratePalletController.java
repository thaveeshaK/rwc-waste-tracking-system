package au.org.resourcework.waste_tracking_api.cratepallet;

import au.org.resourcework.waste_tracking_api.cratepallet.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/crate-pallet-records")
public class CratePalletController {
    private final CratePalletService service;

    public CratePalletController(CratePalletService service) { this.service = service; }

    @PostMapping("/preview")
    public CratePalletResponse preview(@Valid @RequestBody CratePalletRequest input,
            @RequestParam(required = false) Long recordId) {
        return recordId == null ? service.preview(input) : service.previewEdit(recordId, input);
    }

    @PostMapping
    public ResponseEntity<CratePalletResponse> submit(@Valid @RequestBody CratePalletRequest input, Principal principal) {
        var result = service.submit(input, principal.getName());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/crate-pallet-records/" + result.record().id())).body(result.record())
                : ResponseEntity.ok(result.record());
    }

    @GetMapping
    public List<CratePalletResponse> list(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) { return service.list(from, to); }

    @GetMapping("/{id}")
    public CratePalletResponse get(@PathVariable Long id) { return service.get(id); }

    @PutMapping("/{id}")
    public CratePalletResponse update(@PathVariable Long id, @Valid @RequestBody CratePalletRequest input,
            Principal principal) { return service.update(id, input, principal.getName()); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
