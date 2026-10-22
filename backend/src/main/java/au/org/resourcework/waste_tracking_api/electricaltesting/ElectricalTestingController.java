package au.org.resourcework.waste_tracking_api.electricaltesting;
import au.org.resourcework.waste_tracking_api.electricaltesting.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.*;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/electrical-testing")
public class ElectricalTestingController {
    private final ElectricalTestingTallyService tallies;
    private final ElectricalTestingSalesReferenceService references;
    public ElectricalTestingController(ElectricalTestingTallyService tallies, ElectricalTestingSalesReferenceService references) {
        this.tallies=tallies;this.references=references;
    }
    @GetMapping("/categories")
    public List<CategoryResponse> categories() { return tallies.categories(); }
    @PostMapping("/tallies/preview")
    public TallyResponse preview(@Valid @RequestBody TallyRequest input,@RequestParam(required=false) Long recordId) { return recordId == null ? tallies.preview(input) : tallies.previewEdit(recordId,input); }
    @PostMapping("/tallies")
    public ResponseEntity<TallyResponse> create(@Valid @RequestBody TallyRequest input,Principal principal) {
        var result=tallies.submit(input,principal.getName());
        return result.created() ? ResponseEntity.created(URI.create("/api/electrical-testing/tallies/"+result.record().id())).body(result.record()) : ResponseEntity.ok(result.record());
    }
    @GetMapping("/tallies")
    public List<TallyResponse> list(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) { return tallies.list(from,to); }
    @GetMapping("/tallies/{id}") public TallyResponse get(@PathVariable Long id) { return tallies.get(id); }
    @PutMapping("/tallies/{id}") public TallyResponse update(@PathVariable Long id,@Valid @RequestBody TallyRequest input,Principal principal) { return tallies.update(id,input,principal.getName()); }
    @DeleteMapping("/tallies/{id}") public ResponseEntity<Void> delete(@PathVariable Long id,Principal principal) { tallies.delete(id,principal.getName());return ResponseEntity.noContent().build(); }
    @GetMapping("/sales-references") public List<SalesReferenceResponse> references(@RequestParam String month) { return references.list(month); }
    @GetMapping("/sales-references/{id}") public SalesReferenceResponse reference(@PathVariable Long id) { return references.get(id); }
    @PostMapping("/sales-references/preview") public SalesReferenceResponse previewReference(@Valid @RequestBody SalesReferenceRequest input,@RequestParam(required=false) Long recordId) { return recordId==null ? references.preview(input) : references.previewEdit(recordId,input); }
    @PostMapping("/sales-references") public ResponseEntity<SalesReferenceResponse> createReference(@Valid @RequestBody SalesReferenceRequest input,Principal principal) {
        var result=references.submit(input,principal.getName());
        return result.created() ? ResponseEntity.created(URI.create("/api/electrical-testing/sales-references/"+result.record().id())).body(result.record()) : ResponseEntity.ok(result.record());
    }
    @PutMapping("/sales-references/{id}") public SalesReferenceResponse updateReference(@PathVariable Long id,@Valid @RequestBody SalesReferenceRequest input,Principal principal) { return references.update(id,input,principal.getName()); }
    @DeleteMapping("/sales-references/{id}") public ResponseEntity<Void> deleteReference(@PathVariable Long id,Principal principal) { references.delete(id,principal.getName());return ResponseEntity.noContent().build(); }
    @GetMapping("/monthly-summary") public ElectricalTestingSummary summary(@RequestParam String month) { return tallies.summary(month); }
}
