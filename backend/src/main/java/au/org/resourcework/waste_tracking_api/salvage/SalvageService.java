package au.org.resourcework.waste_tracking_api.salvage;

import au.org.resourcework.waste_tracking_api.auth.*;
import au.org.resourcework.waste_tracking_api.reference.*;
import au.org.resourcework.waste_tracking_api.salvage.dto.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class SalvageService {
    private final SalvageRepository records;
    private final MaterialCategoryRepository categories;
    private final AppUserRepository users;
    private final SalvageCalculator calculator;
    private final Clock clock;

    public SalvageService(SalvageRepository records, MaterialCategoryRepository categories,
            AppUserRepository users, SalvageCalculator calculator, Clock clock) {
        this.records = records;
        this.categories = categories;
        this.users = users;
        this.calculator = calculator;
        this.clock = clock;
    }

    public SalvageResponse preview(SalvageRequest input) { return response(calculate(input, null)); }

    @PreAuthorize("hasRole('ADMIN')")
    public SalvageResponse previewEdit(Long id, SalvageRequest input) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        calculated.setVersion(saved.getVersion());
        return response(calculated);
    }

    @Transactional
    public SubmissionResult submit(SalvageRequest input, String username) {
        if (input.submissionId() == null) invalid("A submission ID is required.");
        var actor = actor(username);
        var existing = records.findBySubmissionId(input.submissionId());
        if (existing.isPresent()) return retry(existing.get(), input, actor);
        var calculated = calculate(input, null);
        int inserted = records.insertIfAbsent(input.submissionId(), calculated.getRecordDate(), calculated.getCrew(),
                calculated.getTimeUp(), calculated.getKgUp(), calculated.getTimeDown(), calculated.getKgDown(), actor.getId(), clock.instant());
        var saved = records.findBySubmissionId(input.submissionId()).orElseThrow();
        if (inserted == 0) return retry(saved, input, actor);
        saved.getMaterials().addAll(calculated.getMaterials());
        records.flush();
        return new SubmissionResult(response(saved), true);
    }

    public List<SalvageResponse> list(LocalDate from, LocalDate to) {
        var start = from == null ? LocalDate.of(1, 1, 1) : from;
        var end = to == null ? LocalDate.now(clock) : to;
        if (start.isAfter(end)) invalid("Start date must not be after end date.");
        return records.findActiveBetween(start, end).stream().map(this::response).toList();
    }

    public SalvageResponse get(Long id) { return response(active(id)); }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public SalvageResponse update(Long id, SalvageRequest input, String username) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        saved.setRecordDate(calculated.getRecordDate());
        saved.setCrew(calculated.getCrew());
        saved.setTimeUp(calculated.getTimeUp());
        saved.setKgUp(calculated.getKgUp());
        saved.setTimeDown(calculated.getTimeDown());
        saved.setKgDown(calculated.getKgDown());
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
        var saved = records.lockActiveById(id).orElseThrow(SalvageService::notFound);
        var actor = actor(username);
        var now = clock.instant();
        saved.setDeletedBy(actor);
        saved.setDeletedAt(now);
        saved.setUpdatedBy(actor);
        saved.setUpdatedAt(now);
    }

    private SalvageRecord calculate(SalvageRequest input, SalvageRecord previous) {
        calculator.validateDate(input.recordDate());
        var result = new SalvageRecord();
        result.setRecordDate(input.recordDate());
        result.setCrew(input.crew().strip());
        result.setTimeUp(input.up().time());
        result.setTimeDown(input.down().time());
        addDirection(result, input.up(), SalvageDirection.UP, previous);
        addDirection(result, input.down(), SalvageDirection.DOWN, previous);
        result.setKgUp(input.up().kg().setScale(2));
        result.setKgDown(input.down().kg().setScale(2));
        return result;
    }

    private void addDirection(SalvageRecord result, SalvageRequest.DirectionInput input,
            SalvageDirection direction, SalvageRecord previous) {
        calculator.validateDirection(input.kg(), input.materialsOrEmpty().stream()
                .map(SalvageRequest.MaterialInput::percentage).toList(), direction);
        if (input.kg().signum() == 0) return;
        var seen = new HashSet<Long>();
        for (var material : input.materialsOrEmpty()) {
            if (!seen.add(material.materialCategoryId())) invalid(direction + ": each category may appear only once.");
            var category = categories.findById(material.materialCategoryId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown material category."));
            if (!categories.belongsToContext(category.getId(), direction.materialContext())) {
                invalid(category.getName() + " is not allowed in Salvage " + direction + ".");
            }
            boolean historical = previous != null && previous.getMaterials().stream().anyMatch(m ->
                    m.getDirection() == direction && m.getCategory().getId().equals(category.getId()));
            if (!categories.isAvailable(category.getId(), direction.materialContext()) && !historical) {
                invalid("A selected " + direction + " material category is inactive.");
            }
            if (material.percentage().signum() > 0) {
                result.getMaterials().add(new SalvageMaterialBreakdown(direction, category, material.percentage().setScale(2),
                        calculator.materialWeight(input.kg(), material.percentage())));
            }
        }
    }

    private SubmissionResult retry(SalvageRecord saved, SalvageRequest input, AppUser actor) {
        if (saved.getDeletedAt() != null || !saved.getCreatedBy().getId().equals(actor.getId())
                || !saved.getRecordDate().equals(input.recordDate()) || !saved.getCrew().equals(input.crew().strip())
                || !saved.getTimeUp().equals(input.up().time()) || !saved.getTimeDown().equals(input.down().time())
                || saved.getKgUp().compareTo(input.up().kg()) != 0 || saved.getKgDown().compareTo(input.down().kg()) != 0
                || !sameMaterials(saved, input.up(), SalvageDirection.UP) || !sameMaterials(saved, input.down(), SalvageDirection.DOWN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This submission ID has already been used. View Records before starting a new submission.");
        }
        return new SubmissionResult(response(saved), false);
    }

    private boolean sameMaterials(SalvageRecord saved, SalvageRequest.DirectionInput input, SalvageDirection direction) {
        if (input.kg().signum() == 0) return saved.getMaterials().stream().noneMatch(m -> m.getDirection() == direction);
        var requested = new HashMap<Long, BigDecimal>();
        for (var material : input.materialsOrEmpty()) {
            if (requested.put(material.materialCategoryId(), material.percentage()) != null) return false;
        }
        requested.values().removeIf(value -> value.signum() == 0);
        var stored = saved.getMaterials().stream().filter(m -> m.getDirection() == direction).toList();
        return requested.size() == stored.size() && stored.stream().allMatch(m -> requested.containsKey(m.getCategory().getId())
                && requested.get(m.getCategory().getId()).compareTo(m.getPercentage()) == 0);
    }

    private SalvageResponse response(SalvageRecord record) {
        return new SalvageResponse(record.getId(), record.getVersion(), record.getSubmissionId(), record.getRecordDate(), record.getCrew(),
                directionResponse(record, SalvageDirection.UP, record.getTimeUp(), record.getKgUp()),
                directionResponse(record, SalvageDirection.DOWN, record.getTimeDown(), record.getKgDown()),
                record.getCreatedBy() == null ? null : record.getCreatedBy().getUsername(), record.getCreatedAt(),
                record.getUpdatedBy() == null ? null : record.getUpdatedBy().getUsername(), record.getUpdatedAt());
    }

    private SalvageResponse.DirectionResponse directionResponse(SalvageRecord record, SalvageDirection direction,
            LocalTime time, BigDecimal kg) {
        var rows = record.getMaterials().stream().filter(m -> m.getDirection() == direction)
                .sorted(Comparator.comparing(m -> m.getCategory().getId()))
                .map(m -> new SalvageResponse.MaterialResponse(m.getCategory().getId(), m.getCategory().getName(),
                        m.getPercentage().toPlainString(), m.getMaterialWeight().toPlainString())).toList();
        return new SalvageResponse.DirectionResponse(time, kg.toPlainString(), rows);
    }

    private SalvageRecord active(Long id) { return records.findByIdAndDeletedAtIsNull(id).orElseThrow(SalvageService::notFound); }
    private AppUser actor(String username) { return users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)); }
    private static void checkVersion(SalvageRequest input, SalvageRecord saved) {
        if (input.version() == null) invalid("The record version is required for a correction.");
        if (!saved.getVersion().equals(input.version())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This record has changed. Reload it from Records before correcting it.");
    }
    private static void invalid(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Salvage record not found."); }
    public record SubmissionResult(SalvageResponse record, boolean created) {}
}
