package au.org.resourcework.waste_tracking_api.records;

import au.org.resourcework.waste_tracking_api.cratepallet.CratePalletService;
import au.org.resourcework.waste_tracking_api.salvage.SalvageService;
import au.org.resourcework.waste_tracking_api.pickup.PickupService;
import au.org.resourcework.waste_tracking_api.electricalcratepallet.ElectricalCratePalletService;
import au.org.resourcework.waste_tracking_api.collectables.CollectableService;
import au.org.resourcework.waste_tracking_api.whitegoods.WhitegoodsService;
import au.org.resourcework.waste_tracking_api.electricaltesting.ElectricalTestingTallyService;
import au.org.resourcework.waste_tracking_api.ewastepickup.EwastePickupService;
import au.org.resourcework.waste_tracking_api.scrap.ScrapService;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly=true)
public class RecordsService {
    public record Item(String workflow, Object record) {}
    private final RecordsRepository repository;
    private final CratePalletService cratepallet;
    private final SalvageService salvage;
    private final PickupService pickup;
    private final ElectricalCratePalletService electricalcratepallet;
    private final CollectableService collectables;
    private final WhitegoodsService whitegoods;
    private final ElectricalTestingTallyService electricaltesting;
    private final EwastePickupService ewastepickup;
    private final ScrapService scrap;

    public RecordsService(RecordsRepository repository, CratePalletService cratepallet, SalvageService salvage, PickupService pickup, ElectricalCratePalletService electricalcratepallet, CollectableService collectables, WhitegoodsService whitegoods, ElectricalTestingTallyService electricaltesting, EwastePickupService ewastepickup, ScrapService scrap) {
        this.repository=repository;
        this.cratepallet=cratepallet;
        this.salvage=salvage;
        this.pickup=pickup;
        this.electricalcratepallet=electricalcratepallet;
        this.collectables=collectables;
        this.whitegoods=whitegoods;
        this.electricaltesting=electricaltesting;
        this.ewastepickup=ewastepickup;
        this.scrap=scrap;
    }
    public PageResult<Item> list(String workflow,LocalDate from,LocalDate to,int page,int size) {
        PageResult.validate(page,size);
        if(workflow!=null && workflow.isBlank()) workflow=null;
        if(workflow!=null && !Set.of("CRATE_PALLET","SALVAGE","PICKUP","ELECTRICAL_CRATE_PALLET","COLLECTABLES","WHITEGOODS","ELECTRICAL_TESTING","EWASTE_PICKUP","SCRAP").contains(workflow)) bad("Unknown workflow.");
        LocalDate start=from==null?LocalDate.of(1,1,1):from;
        LocalDate end=to==null?LocalDate.of(9998,12,31):to;
        if(start.getYear()<1 || end.getYear()>9998 || start.isAfter(end)) bad("Invalid record date range.");
        long total=repository.count(workflow,start,end);
        int actual=Math.min(page,PageResult.lastPage(total,size));
        // Hydrate only the bounded database page through existing DTO services, not whole workflow lists.
        var items=repository.page(workflow,start,end,actual,size).stream().map(key->new Item(key.workflow(),switch(key.workflow()) {
            case "CRATE_PALLET" -> cratepallet.get(key.id());
            case "SALVAGE" -> salvage.get(key.id());
            case "PICKUP" -> pickup.get(key.id());
            case "ELECTRICAL_CRATE_PALLET" -> electricalcratepallet.get(key.id());
            case "COLLECTABLES" -> collectables.get(key.id());
            case "WHITEGOODS" -> whitegoods.get(key.id());
            case "ELECTRICAL_TESTING" -> electricaltesting.get(key.id());
            case "EWASTE_PICKUP" -> ewastepickup.get(key.id());
            case "SCRAP" -> scrap.get(key.id());
            default -> throw new IllegalStateException("Unknown stored workflow");
        })).toList();
        return new PageResult<>(items,actual,size,total,(total+size-1)/size);
    }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
