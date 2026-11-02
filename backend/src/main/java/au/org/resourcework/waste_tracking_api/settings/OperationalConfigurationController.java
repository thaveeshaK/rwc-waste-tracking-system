package au.org.resourcework.waste_tracking_api.settings;

import org.springframework.web.bind.annotation.*;

@RestController
public class OperationalConfigurationController {
    private final OperationalConfiguration configuration;
    public OperationalConfigurationController(OperationalConfiguration configuration) { this.configuration=configuration; }
    @GetMapping("/api/operational-configuration")
    public SettingsData.Operational get() { return configuration.operational(); }
}
