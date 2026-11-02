package au.org.resourcework.waste_tracking_api.settings;

import java.security.Principal;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static au.org.resourcework.waste_tracking_api.settings.SettingsData.*;

@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
public class SettingsController {
    private final SettingsService service;
    public SettingsController(SettingsService service) { this.service=service; }
    @GetMapping public Configuration get() { return service.configuration(); }
    @PutMapping public Configuration update(@Valid @RequestBody Configuration c,Principal actor) { return service.update(c,actor.getName()); }
    @GetMapping("/container-types") public List<Reference> containers() { return service.references(true); }
    @PostMapping("/container-types") public Reference createContainer(@Valid @RequestBody ReferenceInput input,Principal actor) { return service.save(true,null,input,actor.getName()); }
    @PutMapping("/container-types/{id}") public Reference updateContainer(@PathVariable Long id,@Valid @RequestBody ReferenceInput input,Principal actor) { return service.save(true,id,input,actor.getName()); }
    @GetMapping("/material-categories") public List<Reference> materials() { return service.references(false); }
    @PostMapping("/material-categories") public Reference createMaterial(@Valid @RequestBody ReferenceInput input,Principal actor) { return service.save(false,null,input,actor.getName()); }
    @PutMapping("/material-categories/{id}") public Reference updateMaterial(@PathVariable Long id,@Valid @RequestBody ReferenceInput input,Principal actor) { return service.save(false,id,input,actor.getName()); }
}
