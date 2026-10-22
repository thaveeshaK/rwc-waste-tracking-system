package au.org.resourcework.waste_tracking_api.whitegoods;

import au.org.resourcework.waste_tracking_api.auth.*;
import au.org.resourcework.waste_tracking_api.whitegoods.dto.*;
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
public class WhitegoodsService {
    private final WhitegoodsRepository records;
    private final AppUserRepository users;
    private final WhitegoodsCalculator calculator;
    private final Clock clock;

    public WhitegoodsService(WhitegoodsRepository records,
            AppUserRepository users,
            WhitegoodsCalculator calculator, Clock clock) {
        this.records = records;
        this.users = users;
        this.calculator = calculator;
        this.clock = clock;
    }

    public WhitegoodsResponse preview(WhitegoodsRequest input) {
        return response(calculate(input, null));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public WhitegoodsResponse previewEdit(Long id, WhitegoodsRequest input) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        calculated.setVersion(saved.getVersion());
        return response(calculated);
    }

    @Transactional
    public SubmissionResult submit(WhitegoodsRequest input, String username) {
        if (input.submissionId() == null) badRequest("A submission ID is required.");
        var actor = actor(username);
        var existing = records.findBySubmissionId(input.submissionId());
        if (existing.isPresent()) return retry(existing.get(), input, actor);

        var calculated = calculate(input, null);
        int inserted = records.insertIfAbsent(input.submissionId(), input.recordDate(),
                calculated.getCount(), calculated.getKgPerItem(), calculated.getEstimatedWeight(), actor.getId(), clock.instant());
        var saved = records.findBySubmissionId(input.submissionId()).orElseThrow();
        if (inserted == 0) return retry(saved, input, actor);
        records.flush();
        return new SubmissionResult(response(saved), true);
    }

    public List<WhitegoodsResponse> list(LocalDate from, LocalDate to) {
        var start = from == null ? LocalDate.of(1, 1, 1) : from;
        var end = to == null ? LocalDate.now(clock) : to;
        if (start.isAfter(end)) badRequest("Start date must not be after end date.");
        return records.findActiveBetween(start, end).stream().map(this::response).toList();
    }

    public WhitegoodsResponse get(Long id) { return response(active(id)); }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public WhitegoodsResponse update(Long id, WhitegoodsRequest input, String username) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        saved.setRecordDate(calculated.getRecordDate());
        saved.setCount(calculated.getCount());
        saved.setKgPerItem(calculated.getKgPerItem());
        saved.setEstimatedWeight(calculated.getEstimatedWeight());
        saved.setUpdatedBy(actor(username));
        saved.setUpdatedAt(clock.instant());
        records.flush();
        return response(saved);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id, String username) {
        var saved = records.lockActiveById(id).orElseThrow(WhitegoodsService::notFound);
        var actor = actor(username);
        var now = clock.instant();
        saved.setDeletedBy(actor);
        saved.setDeletedAt(now);
        saved.setUpdatedBy(actor);
        saved.setUpdatedAt(now);
    }

    private WhitegoodsRecord calculate(WhitegoodsRequest input, WhitegoodsRecord previous) {
        int count = calculator.validate(input.recordDate(), input.count());
        var result = new WhitegoodsRecord();
        result.setRecordDate(input.recordDate());
        result.setCount(count);
        result.setKgPerItem(previous == null ? WhitegoodsCalculator.KG_PER_ITEM : previous.getKgPerItem());
        result.setEstimatedWeight(BigDecimal.valueOf(count).multiply(result.getKgPerItem()));
        return result;
    }

    private SubmissionResult retry(WhitegoodsRecord saved, WhitegoodsRequest input, AppUser actor) {
        if (saved.getDeletedAt() != null || !saved.getCreatedBy().getId().equals(actor.getId())
                || !saved.getRecordDate().equals(input.recordDate())
                || BigDecimal.valueOf(saved.getCount()).compareTo(input.count()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This submission ID has already been used. View Records before starting a new submission.");
        }
        return new SubmissionResult(response(saved), false);
    }

    public WhitegoodsMonthlySummary monthlySummary(String value) {
        var month = calculator.month(value);
        long total = records.sumActiveCount(month.atDay(1), month.plusMonths(1).atDay(1));
        return new WhitegoodsMonthlySummary(month.toString(), total, WhitegoodsCalculator.KG_PER_ITEM.toPlainString(),
                calculator.estimatedWeight(total).toPlainString());
    }

    private WhitegoodsRecord active(Long id) {
        return records.findByIdAndDeletedAtIsNull(id).orElseThrow(WhitegoodsService::notFound);
    }

    private AppUser actor(String username) {
        return users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private WhitegoodsResponse response(WhitegoodsRecord record) {
        return new WhitegoodsResponse(record.getId(), record.getVersion(), record.getSubmissionId(), record.getRecordDate(),
                record.getCount(), record.getKgPerItem().toPlainString(), record.getEstimatedWeight().toPlainString(),
                record.getCreatedBy() == null ? null : record.getCreatedBy().getUsername(), record.getCreatedAt(),
                record.getUpdatedBy() == null ? null : record.getUpdatedBy().getUsername(), record.getUpdatedAt());
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static void checkVersion(WhitegoodsRequest input, WhitegoodsRecord saved) {
        if (input.version() == null) badRequest("The record version is required for a correction.");
        if (!saved.getVersion().equals(input.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This record has changed. Reload it from Records before correcting it.");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found.");
    }

    public record SubmissionResult(WhitegoodsResponse record, boolean created) {}
}
