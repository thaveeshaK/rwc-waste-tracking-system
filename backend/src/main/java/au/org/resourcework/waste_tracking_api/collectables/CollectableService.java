package au.org.resourcework.waste_tracking_api.collectables;

import au.org.resourcework.waste_tracking_api.auth.*;
import au.org.resourcework.waste_tracking_api.collectables.dto.*;
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
public class CollectableService {
    private final CollectableRepository records;
    private final MaterialCategoryRepository categories;
    private final AppUserRepository users;
    private final CollectableCalculator calculator;
    private final Clock clock;

    public CollectableService(CollectableRepository records,
            MaterialCategoryRepository categories, AppUserRepository users,
            CollectableCalculator calculator, Clock clock) {
        this.records = records;
        this.categories = categories;
        this.users = users;
        this.calculator = calculator;
        this.clock = clock;
    }

    public CollectableResponse preview(CollectableRequest input) {
        return response(calculate(input, null));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CollectableResponse previewEdit(Long id, CollectableRequest input) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        calculated.setVersion(saved.getVersion());
        return response(calculated);
    }

    @Transactional
    public SubmissionResult submit(CollectableRequest input, String username) {
        if (input.submissionId() == null) badRequest("A submission ID is required.");
        var actor = actor(username);
        var existing = records.findBySubmissionId(input.submissionId());
        if (existing.isPresent()) return retry(existing.get(), input, actor);

        var calculated = calculate(input, null);
        int inserted = records.insertIfAbsent(input.submissionId(), input.recordDate(),
                calculated.getGrossWeight(), calculated.getTareWeight(), calculated.getNetWeight(), actor.getId(), clock.instant());
        var saved = records.findBySubmissionId(input.submissionId()).orElseThrow();
        if (inserted == 0) return retry(saved, input, actor);
        saved.getMaterials().addAll(calculated.getMaterials());
        records.flush();
        return new SubmissionResult(response(saved), true);
    }

    public List<CollectableResponse> list(LocalDate from, LocalDate to) {
        var start = from == null ? LocalDate.of(1, 1, 1) : from;
        var end = to == null ? LocalDate.now(clock) : to;
        if (start.isAfter(end)) badRequest("Start date must not be after end date.");
        return records.findActiveBetween(start, end).stream().map(this::response).toList();
    }

    public CollectableResponse get(Long id) { return response(active(id)); }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CollectableResponse update(Long id, CollectableRequest input, String username) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        saved.setRecordDate(calculated.getRecordDate());
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
        var saved = records.lockActiveById(id).orElseThrow(CollectableService::notFound);
        var actor = actor(username);
        var now = clock.instant();
        saved.setDeletedBy(actor);
        saved.setDeletedAt(now);
        saved.setUpdatedBy(actor);
        saved.setUpdatedAt(now);
    }

    private CollectableRecord calculate(CollectableRequest input, CollectableRecord previous) {
        var net = calculator.net(input.recordDate(), input.grossWeight(),
                input.materials().stream().map(CollectableRequest.MaterialInput::percentage).toList());
        var seen = new HashSet<Long>();
        var result = new CollectableRecord();
        result.setRecordDate(input.recordDate());
        result.setGrossWeight(input.grossWeight().setScale(2));
        result.setTareWeight(CollectableCalculator.TARE);
        result.setNetWeight(net);
        for (var material : input.materials()) {
            if (!seen.add(material.materialCategoryId())) badRequest("Each material category may appear only once.");
            var category = categories.findById(material.materialCategoryId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown material category."));
            if (!categories.belongsToContext(category.getId(), MaterialContext.COLLECTABLES.name())) {
                badRequest("This material category is not allowed in Collectable.");
            }
            boolean previouslyUsed = previous != null && previous.getMaterials().stream()
                    .anyMatch(m -> m.getCategory().getId().equals(category.getId()));
            if (!categories.isAvailable(category.getId(), MaterialContext.COLLECTABLES.name()) && !previouslyUsed) {
                badRequest("A selected material category is inactive.");
            }
            if (material.percentage().signum() > 0) {
                result.getMaterials().add(new CollectableMaterialBreakdown(category, material.percentage().setScale(2),
                        calculator.materialWeight(net, material.percentage())));
            }
        }
        return result;
    }

    private SubmissionResult retry(CollectableRecord saved, CollectableRequest input, AppUser actor) {
        var requested = new HashMap<Long, BigDecimal>();
        boolean duplicates = false;
        for (var material : input.materials()) {
            if (requested.put(material.materialCategoryId(), material.percentage()) != null) duplicates = true;
        }
        requested.values().removeIf(value -> value.signum() == 0);
        boolean sameMaterials = requested.size() == saved.getMaterials().size()
                && saved.getMaterials().stream().allMatch(m -> requested.containsKey(m.getCategory().getId())
                && requested.get(m.getCategory().getId()).compareTo(m.getPercentage()) == 0);
        if (duplicates || saved.getDeletedAt() != null || !saved.getCreatedBy().getId().equals(actor.getId())
                || !saved.getRecordDate().equals(input.recordDate())
                || saved.getGrossWeight().compareTo(input.grossWeight()) != 0 || !sameMaterials) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This submission ID has already been used. View Records before starting a new submission.");
        }
        return new SubmissionResult(response(saved), false);
    }

    private CollectableRecord active(Long id) {
        return records.findByIdAndDeletedAtIsNull(id).orElseThrow(CollectableService::notFound);
    }

    private AppUser actor(String username) {
        return users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private CollectableResponse response(CollectableRecord record) {
        var materials = record.getMaterials().stream()
                .sorted(Comparator.comparing(m -> m.getCategory().getId()))
                .map(m -> new CollectableResponse.MaterialResponse(m.getCategory().getId(), m.getCategory().getName(),
                        m.getPercentage().toPlainString(), m.getMaterialWeight().toPlainString())).toList();
        return new CollectableResponse(record.getId(), record.getVersion(), record.getSubmissionId(), record.getRecordDate(),
                record.getGrossWeight().toPlainString(), record.getTareWeight().toPlainString(),
                record.getNetWeight().toPlainString(), materials,
                record.getCreatedBy() == null ? null : record.getCreatedBy().getUsername(), record.getCreatedAt(),
                record.getUpdatedBy() == null ? null : record.getUpdatedBy().getUsername(), record.getUpdatedAt());
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static void checkVersion(CollectableRequest input, CollectableRecord saved) {
        if (input.version() == null) badRequest("The record version is required for a correction.");
        if (!saved.getVersion().equals(input.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This record has changed. Reload it from Records before correcting it.");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found.");
    }

    public record SubmissionResult(CollectableResponse record, boolean created) {}
}
