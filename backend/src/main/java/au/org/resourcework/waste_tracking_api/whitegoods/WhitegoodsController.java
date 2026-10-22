package au.org.resourcework.waste_tracking_api.whitegoods;

import au.org.resourcework.waste_tracking_api.whitegoods.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/whitegoods-records")
public class WhitegoodsController {
    private final WhitegoodsService service;

    public WhitegoodsController(WhitegoodsService service) { this.service = service; }

    @PostMapping("/preview")
    public WhitegoodsResponse preview(@Valid @RequestBody WhitegoodsRequest input,
            @RequestParam(required = false) Long recordId) {
        return recordId == null ? service.preview(input) : service.previewEdit(recordId, input);
    }

    @PostMapping
    public ResponseEntity<WhitegoodsResponse> submit(@Valid @RequestBody WhitegoodsRequest input, Principal principal) {
        var result = service.submit(input, principal.getName());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/whitegoods-records/" + result.record().id())).body(result.record())
                : ResponseEntity.ok(result.record());
    }

    @GetMapping
    public List<WhitegoodsResponse> list(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) { return service.list(from, to); }

    @GetMapping("/{id}")
    public WhitegoodsResponse get(@PathVariable Long id) { return service.get(id); }

    @PutMapping("/{id}")
    public WhitegoodsResponse update(@PathVariable Long id, @Valid @RequestBody WhitegoodsRequest input,
            Principal principal) { return service.update(id, input, principal.getName()); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/monthly-summary")
    public WhitegoodsMonthlySummary monthlySummary(@RequestParam String month) { return service.monthlySummary(month); }
}
