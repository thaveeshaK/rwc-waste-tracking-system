package au.org.resourcework.waste_tracking_api.ewastepickup;

import au.org.resourcework.waste_tracking_api.auth.*;
import au.org.resourcework.waste_tracking_api.ewastepickup.dto.*;
import au.org.resourcework.waste_tracking_api.reference.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class EwastePickupService {
    private final EwastePickupRepository records;
    private final ContainerTypeRepository containers;
    private final AppUserRepository users;

    private final Clock clock;

    public EwastePickupService(EwastePickupRepository records,
            ContainerTypeRepository containers, AppUserRepository users,
            Clock clock) {
        this.records = records;
        this.containers = containers;
        this.users = users;

        this.clock = clock;
    }

    public EwastePickupResponse preview(EwastePickupRequest input) {
        return response(calculate(input, null));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public EwastePickupResponse previewEdit(Long id, EwastePickupRequest input) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        calculated.setVersion(saved.getVersion());
        return response(calculated);
    }

    @Transactional
    public SubmissionResult submit(EwastePickupRequest input, String username) {
        if (input.submissionId() == null) badRequest("A submission ID is required.");
        var actor = actor(username);
        var existing = records.findBySubmissionId(input.submissionId());
        if (existing.isPresent()) return retry(existing.get(), input, actor);

        var calculated = calculate(input, null);
        int inserted = records.insertIfAbsent(input.submissionId(), input.recordDate(),
                calculated.getOrganisation(), calculated.getEnteredWeight(), calculated.getTotalTareWeight(), calculated.getNetWeight(), actor.getId(), clock.instant());
        var saved = records.findBySubmissionId(input.submissionId()).orElseThrow();
        if (inserted == 0) return retry(saved, input, actor);
        saved.getContainers().addAll(calculated.getContainers());
        records.flush();
        return new SubmissionResult(response(saved), true);
    }

    public List<EwastePickupResponse> list(LocalDate from, LocalDate to) {
        var start = from == null ? LocalDate.of(1, 1, 1) : from;
        var end = to == null ? LocalDate.now(clock) : to;
        if (start.isAfter(end)) badRequest("Start date must not be after end date.");
        return records.findActiveBetween(start, end).stream().map(this::response).toList();
    }

    public EwastePickupResponse get(Long id) { return response(active(id)); }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public EwastePickupResponse update(Long id, EwastePickupRequest input, String username) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        saved.setRecordDate(calculated.getRecordDate());
        saved.setOrganisation(calculated.getOrganisation());
        saved.setNetWeight(calculated.getNetWeight());
        saved.setEnteredWeight(calculated.getEnteredWeight());
        saved.setTotalTareWeight(calculated.getTotalTareWeight());
        saved.getContainers().clear();
        saved.getContainers().addAll(calculated.getContainers());
        saved.setUpdatedBy(actor(username));
        saved.setUpdatedAt(clock.instant());
        records.flush();
        return response(saved);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id, String username) {
        var saved = records.lockActiveById(id).orElseThrow(EwastePickupService::notFound);
        var actor = actor(username);
        var now = clock.instant();
        saved.setDeletedBy(actor);
        saved.setDeletedAt(now);
        saved.setUpdatedBy(actor);
        saved.setUpdatedAt(now);
    }

    private EwastePickupRecord calculate(EwastePickupRequest input, EwastePickupRecord previous) {
        if(input.recordDate()==null || input.recordDate().getYear()<1 || input.recordDate().isAfter(LocalDate.now(clock))) badRequest("Record date must be past or current in Australia/Hobart.");
        if(input.organisation()==null || input.organisation().strip().isEmpty() || input.organisation().length()>255) badRequest("Organisation is required (maximum 255 characters).");
        if(input.enteredWeight()==null || input.enteredWeight().signum()<=0 || input.enteredWeight().scale()>2 || input.enteredWeight().compareTo(new BigDecimal("99999999.99"))>0) badRequest("Entered weight must be positive with at most two decimals.");
        var result=new EwastePickupRecord();
        result.setRecordDate(input.recordDate());result.setOrganisation(input.organisation().strip());
        result.setEnteredWeight(input.enteredWeight().setScale(2));
        var seen=new HashSet<Long>();var total=BigDecimal.ZERO.setScale(2);
        for(var line:input.containers()) {
            if(!seen.add(line.containerTypeId())) badRequest("Each container type may appear only once; use its quantity.");
            if(line.quantity()==null || line.quantity().signum()<=0 || line.quantity().stripTrailingZeros().scale()>0 || line.quantity().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE))>0) badRequest("Container quantity must be a positive integer.");
            var old=previous==null?null:previous.getContainers().stream().filter(c->c.getContainerType().getId().equals(line.containerTypeId())).findFirst().orElse(null);
            var type=containers.findById(line.containerTypeId()).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown container type."));
            if(old==null && !containers.isAvailable(type.getId(),ContainerContext.EWASTE_PICKUP.name())) badRequest("Select an active E-waste Pickup container.");
            var tare=old==null?type.getTareWeight():old.getTarePerUnit();
            var subtotal=tare.multiply(line.quantity()).setScale(2);
            total=total.add(subtotal);
            result.getContainers().add(new EwastePickupContainer(type,old==null?type.getName():old.getContainerName(),tare,line.quantity().intValueExact(),subtotal));
        }
        if(total.compareTo(input.enteredWeight())>=0) badRequest("Entered weight must exceed total tare; net E-waste weight must be positive.");
        result.setTotalTareWeight(total);result.setNetWeight(input.enteredWeight().subtract(total).setScale(2));
        return result;
    }

    private SubmissionResult retry(EwastePickupRecord saved, EwastePickupRequest input, AppUser actor) {
        var requested=new HashMap<Long,Integer>();boolean duplicates=false;
        for(var line:input.containers()) if(requested.put(line.containerTypeId(),line.quantity().intValueExact())!=null)duplicates=true;
        boolean same=requested.size()==saved.getContainers().size() && saved.getContainers().stream().allMatch(c->Objects.equals(requested.get(c.getContainerType().getId()),c.getQuantity()));
        if(duplicates || !same || saved.getDeletedAt()!=null || !saved.getCreatedBy().getId().equals(actor.getId()) || !saved.getRecordDate().equals(input.recordDate()) || !saved.getOrganisation().equals(input.organisation().strip()) || saved.getEnteredWeight().compareTo(input.enteredWeight())!=0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This submission ID has already been used. View Records before starting a new submission.");
        }
        return new SubmissionResult(response(saved), false);
    }

    private EwastePickupRecord active(Long id) {
        return records.findByIdAndDeletedAtIsNull(id).orElseThrow(EwastePickupService::notFound);
    }

    private AppUser actor(String username) {
        return users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private EwastePickupResponse response(EwastePickupRecord record) {
        var lines=record.getContainers().stream().sorted(Comparator.comparing(c->c.getContainerType().getId())).map(c->new EwastePickupResponse.ContainerResponse(c.getContainerType().getId(),c.getContainerName(),c.getTarePerUnit().toPlainString(),c.getQuantity(),c.getTareSubtotal().toPlainString())).toList();
        return new EwastePickupResponse(record.getId(),record.getVersion(),record.getSubmissionId(),record.getRecordDate(),record.getOrganisation(),record.getEnteredWeight().toPlainString(),record.getTotalTareWeight().toPlainString(),record.getNetWeight().toPlainString(),lines,record.getCreatedBy()==null?null:record.getCreatedBy().getUsername(),record.getCreatedAt(),record.getUpdatedBy()==null?null:record.getUpdatedBy().getUsername(),record.getUpdatedAt());
    }
    public List<String> organisations(String query) {
        if(query.length()>255)badRequest("Organisation query is too long.");
        return records.organisations(query.strip());
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static void checkVersion(EwastePickupRequest input, EwastePickupRecord saved) {
        if (input.version() == null) badRequest("The record version is required for a correction.");
        if (!saved.getVersion().equals(input.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This record has changed. Reload it from Records before correcting it.");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found.");
    }

    public record SubmissionResult(EwastePickupResponse record, boolean created) {}
}
