package au.org.resourcework.waste_tracking_api.electricaltesting;

import au.org.resourcework.waste_tracking_api.auth.*;
import au.org.resourcework.waste_tracking_api.electricaltesting.dto.*;
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
public class ElectricalTestingTallyService {
    private final ElectricalTestingTallyRepository records;
    private final AppUserRepository users;
    private final ElectricalTestingValidation calculator;
    private final Clock clock;
    private final ElectricalTestingCategoryRepository categories;

    public ElectricalTestingTallyService(ElectricalTestingTallyRepository records,
            AppUserRepository users,
            ElectricalTestingValidation calculator, Clock clock,ElectricalTestingCategoryRepository categories) {
        this.records = records;
        this.users = users;
        this.calculator = calculator;
        this.clock = clock;
        this.categories=categories;
    }

    public TallyResponse preview(TallyRequest input) {
        return response(calculate(input, null));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public TallyResponse previewEdit(Long id, TallyRequest input) {
        var saved = active(id);
        ElectricalTestingValidation.version(input.version(), saved.getVersion());
        var calculated = calculate(input, saved);
        calculated.setVersion(saved.getVersion());
        return response(calculated);
    }

    @Transactional
    public SubmissionResult submit(TallyRequest input, String username) {
        if (input.submissionId() == null) badRequest("A submission ID is required.");
        var actor = actor(username);
        var existing = records.findBySubmissionId(input.submissionId());
        if (existing.isPresent()) return retry(existing.get(), input, actor);

        var calculated = calculate(input, null);
        int inserted = records.insertIfAbsent(input.submissionId(), input.recordDate(),
                calculated.getCategory() == null ? null : calculated.getCategory().getId(), calculated.getTallyType().name(), calculated.getCount(), actor.getId(), clock.instant());
        var saved = records.findBySubmissionId(input.submissionId()).orElseThrow();
        if (inserted == 0) return retry(saved, input, actor);
        records.flush();
        return new SubmissionResult(response(saved), true);
    }

    public List<TallyResponse> list(LocalDate from, LocalDate to) {
        var start = from == null ? LocalDate.of(1,1,1) : from;
        var end = to == null ? LocalDate.now(clock) : to;
        if (start.isAfter(end)) badRequest("Start date must not be after end date.");
        return records.findActiveBetween(start,end).stream().map(this::response).toList();
    }

    public TallyResponse get(Long id) { return response(active(id)); }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public TallyResponse update(Long id, TallyRequest input, String username) {
        var saved = active(id);
        ElectricalTestingValidation.version(input.version(), saved.getVersion());
        var calculated = calculate(input, saved);
        saved.setRecordDate(calculated.getRecordDate());
        saved.setCategory(calculated.getCategory());
        saved.setTallyType(calculated.getTallyType());
        saved.setCount(calculated.getCount());
        saved.setUpdatedBy(actor(username));
        saved.setUpdatedAt(clock.instant());
        records.flush();
        return response(saved);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id, String username) {
        var saved = records.lockActiveById(id).orElseThrow(ElectricalTestingTallyService::notFound);
        var actor = actor(username);
        var now = clock.instant();
        saved.setDeletedBy(actor);
        saved.setDeletedAt(now);
        saved.setUpdatedBy(actor);
        saved.setUpdatedAt(now);
    }

    private ElectricalTestingTally calculate(TallyRequest input, ElectricalTestingTally previous) {
        calculator.date(input.recordDate());
        if (input.tallyType() == null) badRequest("Tally type is required.");
        var result = new ElectricalTestingTally();
        result.setRecordDate(input.recordDate());
        result.setTallyType(input.tallyType());
        result.setCount(calculator.count(input.count(), false));
        if (input.tallyType().requiresCategory()) result.setCategory(calculator.category(input.categoryId(), previous == null ? null : previous.getCategory()));
        else if (input.categoryId() != null) badRequest("Diversion counters must not have an electrical item category.");
        return result;
    }

    private SubmissionResult retry(ElectricalTestingTally saved, TallyRequest input, AppUser actor) {
        if (saved.getDeletedAt() != null || !saved.getCreatedBy().getId().equals(actor.getId())
                || !saved.getRecordDate().equals(input.recordDate()) || saved.getTallyType() != input.tallyType()
                || !Objects.equals(saved.getCategory() == null ? null : saved.getCategory().getId(), input.categoryId())
                || BigDecimal.valueOf(saved.getCount()).compareTo(input.count()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This submission ID has already been used. Check saved records before starting another submission.");
        }
        return new SubmissionResult(response(saved), false);
    }

    public List<CategoryResponse> categories() { return categories.findByActiveTrueOrderByDisplayOrderAsc().stream().map(c -> new CategoryResponse(c.getId(),c.getName(),c.getDisplayOrder())).toList(); }
    public ElectricalTestingSummary summary(String value) {
        var start=calculator.month(value);
        var totals=records.totals(start,start.plusMonths(1));
        var rows=categories.findAllByOrderByDisplayOrderAsc().stream().map(category -> new ElectricalTestingSummary.CategoryTotals(
                category.getId(),category.getName(),total(totals,category.getId(),TallyType.UNTESTED_BROKEN_FAILED),
                total(totals,category.getId(),TallyType.TESTED_AND_PRICED),total(totals,category.getId(),TallyType.STOCK_CULL))).toList();
        return new ElectricalTestingSummary(value,rows,total(totals,null,TallyType.DIVERTED_TO_OLS),total(totals,null,TallyType.DIVERTED_TO_COLL));
    }
    private long total(List<ElectricalTestingTallyRepository.Total> rows,Long category,TallyType type) {
        return rows.stream().filter(row -> Objects.equals(row.getCategoryId(),category) && row.getTallyType()==type).mapToLong(ElectricalTestingTallyRepository.Total::getCount).sum();
    }

    private ElectricalTestingTally active(Long id) {
        return records.findByIdAndDeletedAtIsNull(id).orElseThrow(ElectricalTestingTallyService::notFound);
    }

    private AppUser actor(String username) {
        return users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private TallyResponse response(ElectricalTestingTally record) {
        return new TallyResponse(record.getId(),record.getVersion(),record.getSubmissionId(),record.getRecordDate(), record.getCategory() == null ? null : record.getCategory().getId(), record.getCategory() == null ? null : record.getCategory().getName(), record.getTallyType(), record.getCount(),
                record.getCreatedBy() == null ? null : record.getCreatedBy().getUsername(),record.getCreatedAt(),
                record.getUpdatedBy() == null ? null : record.getUpdatedBy().getUsername(),record.getUpdatedAt());
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found.");
    }

    public record SubmissionResult(TallyResponse record, boolean created) {}
}
