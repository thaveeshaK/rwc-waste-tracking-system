package au.org.resourcework.waste_tracking_api.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static au.org.resourcework.waste_tracking_api.settings.SettingsData.*;

/** Internal reads for calculations/headers; never exposes administrative write endpoints. */
@Service
@Transactional(readOnly=true)
public class OperationalConfiguration {
    private final SettingsRepository repository;
    public OperationalConfiguration(SettingsRepository repository) { this.repository=repository; }
    public Configuration current() { return repository.configuration(); }
    public Operational operational() {
        var c=current();
        return new Operational(c.organisationName(),c.siteName(),c.reportDisplayName(),c.whitegoodsKgPerItem().toPlainString(),"Australia/Hobart");
    }
}
