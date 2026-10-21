package au.org.resourcework.waste_tracking_api.electricalcratepallet;

import au.org.resourcework.waste_tracking_api.auth.*;
import au.org.resourcework.waste_tracking_api.electricalcratepallet.dto.*;
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
public class ElectricalCratePalletService {
    private final ElectricalCratePalletRepository records;
    private final ContainerTypeRepository containers;
    private final MaterialCategoryRepository categories;
    private final AppUserRepository users;
    private final ElectricalCratePalletCalculator calculator;
    private final Clock clock;

    public ElectricalCratePalletService(ElectricalCratePalletRepository records, ContainerTypeRepository containers,
            MaterialCategoryRepository categories, AppUserRepository users,
            ElectricalCratePalletCalculator calculator, Clock clock) {
        this.records = records;
        this.containers = containers;
        this.categories = categories;
        this.users = users;
        this.calculator = calculator;
        this.clock = clock;
    }

    public ElectricalCratePalletResponse preview(ElectricalCratePalletRequest input) {
        return response(calculate(input, null));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public ElectricalCratePalletResponse previewEdit(Long id, ElectricalCratePalletRequest input) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        calculated.setVersion(saved.getVersion());
        return response(calculated);
    }

    @Transactional
    public SubmissionResult submit(ElectricalCratePalletRequest input, String username) {
        if (input.submissionId() == null) badRequest("A submission ID is required.");
        var actor = actor(username);
        var existing = records.findBySubmissionId(input.submissionId());
        if (existing.isPresent()) return retry(existing.get(), input, actor);

        var calculated = calculate(input, null);
        int inserted = records.insertIfAbsent(input.submissionId(), input.recordDate(),
                calculated.getContainerType().getId(), calculated.getGrossWeight(), calculated.getTareWeight(),
                calculated.getNetWeight(), actor.getId(), clock.instant());
        var saved = records.findBySubmissionId(input.submissionId()).orElseThrow();
        if (inserted == 0) return retry(saved, input, actor);
        saved.getMaterials().addAll(calculated.getMaterials());
        records.flush();
        return new SubmissionResult(response(saved), true);
    }

    public List<ElectricalCratePalletResponse> list(LocalDate from, LocalDate to) {
        var start = from == null ? LocalDate.of(1, 1, 1) : from;
        var end = to == null ? LocalDate.now(clock) : to;
        if (start.isAfter(end)) badRequest("Start date must not be after end date.");
        return records.findActiveBetween(start, end).stream().map(this::response).toList();
    }

    public ElectricalCratePalletResponse get(Long id) { return response(active(id)); }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ElectricalCratePalletResponse update(Long id, ElectricalCratePalletRequest input, String username) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        saved.setRecordDate(calculated.getRecordDate());
        saved.setContainerType(calculated.getContainerType());
        saved.setGrossWeight(calculated.getGrossWeight());
        saved.setTareWeight(calculated.getTareWeight());
        saved.setNetWeight(calculated.getNetWeight());
        saved.getMaterials().clear();
        saved.getMaterials().addAll(calculated.getMaterials());
        saved.setUpdatedBy(actor(username));
        saved.setUpdatedAt(clock.instant());
        records.flush();
        return response(saved);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id, String username) {
        var saved = records.lockActiveById(id).orElseThrow(ElectricalCratePalletService::notFound);
        var actor = actor(username);
        var now = clock.instant();
        saved.setDeletedBy(actor);
        saved.setDeletedAt(now);
        saved.setUpdatedBy(actor);
        saved.setUpdatedAt(now);
    }

    private ElectricalCratePalletRecord calculate(ElectricalCratePalletRequest input, ElectricalCratePalletRecord previous) {
        var container = containers.findById(input.containerTypeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown container type."));
        if (!containers.belongsToContext(container.getId(), ContainerContext.ELECTRICAL_CRATE_PALLET.name())) {
            badRequest("This container is not allowed in Electrical Crate/Pallet.");
        }
        boolean sameContainer = previous != null && previous.getContainerType().getId().equals(container.getId());
        if (!containers.isAvailable(container.getId(), ContainerContext.ELECTRICAL_CRATE_PALLET.name()) && !sameContainer) badRequest("The selected container type is inactive.");
        var tare = sameContainer ? previous.getTareWeight() : container.getTareWeight();
        var net = calculator.net(input.recordDate(), input.grossWeight(), tare,
                input.materials().stream().map(ElectricalCratePalletRequest.MaterialInput::percentage).toList());
        if (input.materials().size() != 2) badRequest("Both electrical material categories are required.");
        var seen = new HashSet<Long>();
        var result = new ElectricalCratePalletRecord();
        result.setRecordDate(input.recordDate());
        result.setContainerType(container);
        result.setGrossWeight(input.grossWeight().setScale(2));
        result.setTareWeight(tare);
        result.setNetWeight(net);
        for (var material : input.materials()) {
            if (!seen.add(material.materialCategoryId())) badRequest("Each material category may appear only once.");
            var category = categories.findById(material.materialCategoryId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown material category."));
            if (!categories.belongsToContext(category.getId(), MaterialContext.ELECTRICAL_CRATE_PALLET.name())) {
                badRequest("This material category is not allowed in Electrical Crate/Pallet.");
            }
            // Both categories belong to every historical electrical record, including the unstored zero row.
            boolean previouslyUsed = previous != null;
            if (!categories.isAvailable(category.getId(), MaterialContext.ELECTRICAL_CRATE_PALLET.name()) && !previouslyUsed) {
                badRequest("A selected material category is inactive.");
            }
            if (material.percentage().signum() > 0) {
                result.getMaterials().add(new ElectricalMaterialBreakdown(category, material.percentage().setScale(2),
                        calculator.materialWeight(net, material.percentage())));
            }
        }
        return result;
    }

    private SubmissionResult retry(ElectricalCratePalletRecord saved, ElectricalCratePalletRequest input, AppUser actor) {
        var requested = new HashMap<Long, BigDecimal>();
        boolean duplicates = input.materials().size() != 2;
        for (var material : input.materials()) {
            if (!categories.belongsToContext(material.materialCategoryId(), MaterialContext.ELECTRICAL_CRATE_PALLET.name())
                    || requested.put(material.materialCategoryId(), material.percentage()) != null) duplicates = true;
        }
        requested.values().removeIf(value -> value.signum() == 0);
        boolean sameMaterials = requested.size() == saved.getMaterials().size()
                && saved.getMaterials().stream().allMatch(m -> requested.containsKey(m.getCategory().getId())
                && requested.get(m.getCategory().getId()).compareTo(m.getPercentage()) == 0);
        if (duplicates || saved.getDeletedAt() != null || !saved.getCreatedBy().getId().equals(actor.getId())
                || !saved.getRecordDate().equals(input.recordDate())
                || !saved.getContainerType().getId().equals(input.containerTypeId())
                || saved.getGrossWeight().compareTo(input.grossWeight()) != 0 || !sameMaterials) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This submission ID has already been used. View Records before starting a new submission.");
        }
        return new SubmissionResult(response(saved), false);
    }

    private ElectricalCratePalletRecord active(Long id) {
        return records.findByIdAndDeletedAtIsNull(id).orElseThrow(ElectricalCratePalletService::notFound);
    }

    private AppUser actor(String username) {
        return users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private ElectricalCratePalletResponse response(ElectricalCratePalletRecord record) {
        // Both approved categories remain visible, including zero-percentage and inactive historical rows.
        var materials = categories.findForContext(MaterialContext.ELECTRICAL_CRATE_PALLET.name()).stream()
                .map(category -> {
                    var row = record.getMaterials().stream().filter(m -> m.getCategory().getId().equals(category.getId())).findFirst();
                    return new ElectricalCratePalletResponse.MaterialResponse(category.getId(), category.getName(),
                            row.map(m -> m.getPercentage().toPlainString()).orElse("0.00"),
                            row.map(m -> m.getMaterialWeight().toPlainString()).orElse("0.00"));
                }).toList();
        return new ElectricalCratePalletResponse(record.getId(), record.getVersion(), record.getSubmissionId(), record.getRecordDate(),
                record.getContainerType().getId(), record.getContainerType().getName(),
                record.getGrossWeight().toPlainString(), record.getTareWeight().toPlainString(),
                record.getNetWeight().toPlainString(), materials,
                record.getCreatedBy() == null ? null : record.getCreatedBy().getUsername(), record.getCreatedAt(),
                record.getUpdatedBy() == null ? null : record.getUpdatedBy().getUsername(), record.getUpdatedAt());
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static void checkVersion(ElectricalCratePalletRequest input, ElectricalCratePalletRecord saved) {
        if (input.version() == null) badRequest("The record version is required for a correction.");
        if (!saved.getVersion().equals(input.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This record has changed. Reload it from Records before correcting it.");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found.");
    }

    public record SubmissionResult(ElectricalCratePalletResponse record, boolean created) {}
}
