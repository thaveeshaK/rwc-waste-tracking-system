package au.org.resourcework.waste_tracking_api.settings;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static au.org.resourcework.waste_tracking_api.settings.SettingsData.*;

@Service
@Transactional(readOnly=true)
@PreAuthorize("hasRole('ADMIN')")
public class SettingsService {
    private static final Set<String> CONTAINERS=Set.of("CRATE_PALLET","ELECTRICAL_CRATE_PALLET","EWASTE_PICKUP");
    private static final Set<String> MATERIALS=Set.of("CRATE_PALLET","SALVAGE_UP","SALVAGE_DOWN","PICKUP","COLLECTABLES");
    private final SettingsRepository repository;
    public SettingsService(SettingsRepository repository) { this.repository=repository; }
    public Configuration configuration() { return repository.configuration(); }
    @Transactional public Configuration update(Configuration c,String actor) {
        if(!Set.of("kg","tonnes").contains(c.displayUnit()) || !Set.of("CURRENT_MONTH","CURRENT_FINANCIAL_YEAR").contains(c.reportPeriod())) bad("Unknown reporting unit or default period.");
        if(repository.update(c,actor)==0) conflict();
        return configuration();
    }
    public List<Reference> references(boolean container) { return repository.references(container); }
    @Transactional public Reference save(boolean container,Long id,ReferenceInput input,String actor) {
        final Long requestedId=id;
        Reference previous=id==null?null:references(container).stream().filter(r->r.id().equals(requestedId)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Reference not found."));
        if(previous!=null && previous.restricted()) bad("The two electrical material identities and applicability are fixed for this workflow.");
        if(previous!=null && !container && !previous.name().equals(input.name().trim())) bad("Category names are immutable. Add a new category and deactivate the old one instead.");
        if(container && input.contexts().contains("CRATE_PALLET") && input.summaryGroup()==null) bad("Waste Levy summary group is required for Crate/Pallet containers.");
        if(container && input.summaryGroup()!=null && !Set.of("CRATE","PALLET","OTHER").contains(input.summaryGroup())) bad("Invalid Waste Levy summary group.");
        if(container && input.tareWeight()==null) bad("Tare weight is required.");
        if(!(container?CONTAINERS:MATERIALS).containsAll(input.contexts())) bad("Unsupported workflow applicability.");
        if(input.active() && input.contexts().isEmpty()) bad("An active reference needs at least one applicable workflow.");
        if(!container && Set.of("E-waste","Lead Acid Batteries","Whitegoods / Large Appliances").contains(input.name().trim()) && input.contexts().stream().anyMatch(c->!c.startsWith("SALVAGE_"))) bad("This material is restricted to its approved Salvage workflows.");
        if(id==null) id=repository.create(container,input,actor);
        else if(input.version()==null || repository.update(container,id,input,actor)==0) conflict();
        repository.applicability(container,id,input.contexts());
        final Long savedId=id;
        return references(container).stream().filter(r->r.id().equals(savedId)).findFirst().orElseThrow();
    }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static void conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT,"Settings have changed. Reload before saving."); }
}
