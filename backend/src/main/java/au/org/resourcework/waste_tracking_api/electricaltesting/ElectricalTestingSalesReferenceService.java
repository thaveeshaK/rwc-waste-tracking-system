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
public class ElectricalTestingSalesReferenceService {
    private final ElectricalTestingSalesReferenceRepository records;
    private final AppUserRepository users;
    private final ElectricalTestingValidation calculator;
    private final Clock clock;

    public ElectricalTestingSalesReferenceService(ElectricalTestingSalesReferenceRepository records,
            AppUserRepository users,
            ElectricalTestingValidation calculator, Clock clock) {
        this.records = records;
        this.users = users;
        this.calculator = calculator;
        this.clock = clock;
    }

    public SalesReferenceResponse preview(SalesReferenceRequest input) {
        return response(calculate(input, null));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public SalesReferenceResponse previewEdit(Long id, SalesReferenceRequest input) {
        var saved = active(id);
        ElectricalTestingValidation.version(input.version(), saved.getVersion());
        var calculated = calculate(input, saved);
        calculated.setVersion(saved.getVersion());
        return response(calculated);
    }

    @Transactional
    public SubmissionResult submit(SalesReferenceRequest input, String username) {
        if (input.submissionId() == null) badRequest("A submission ID is required.");
        var actor = actor(username);
        var existing = records.findBySubmissionId(input.submissionId());
        if (existing.isPresent()) return retry(existing.get(), input, actor);

        var calculated = calculate(input, null);
        int inserted = records.insertIfAbsent(input.submissionId(), calculated.getReportingMonth(),
                calculated.getCategory().getId(), calculated.getSoldLastMonthCount(), actor.getId(), clock.instant());
        var saved = records.findBySubmissionId(input.submissionId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "A Sold Last Month reference is already submitted for this category and reporting month. Reload the references; Staff cannot replace submitted values."));
        if (inserted == 0) return retry(saved, input, actor);
        records.flush();
        return new SubmissionResult(response(saved), true);
    }

    public List<SalesReferenceResponse> list(String month) {
        return records.findByReportingMonthAndDeletedAtIsNullOrderByCategoryDisplayOrderAsc(calculator.month(month)).stream().map(this::response).toList();
    }

    public SalesReferenceResponse get(Long id) { return response(active(id)); }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public SalesReferenceResponse update(Long id, SalesReferenceRequest input, String username) {
        var saved = active(id);
        ElectricalTestingValidation.version(input.version(), saved.getVersion());
        var calculated = calculate(input, saved);
        saved.setReportingMonth(calculated.getReportingMonth());
        saved.setCategory(calculated.getCategory());
        saved.setSoldLastMonthCount(calculated.getSoldLastMonthCount());
        saved.setUpdatedBy(actor(username));
        saved.setUpdatedAt(clock.instant());
        records.flush();
        return response(saved);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id, String username) {
        var saved = records.lockActiveById(id).orElseThrow(ElectricalTestingSalesReferenceService::notFound);
        var actor = actor(username);
        var now = clock.instant();
        saved.setDeletedBy(actor);
        saved.setDeletedAt(now);
        saved.setUpdatedBy(actor);
        saved.setUpdatedAt(now);
    }

    private ElectricalTestingSalesReference calculate(SalesReferenceRequest input, ElectricalTestingSalesReference previous) {
        var result = new ElectricalTestingSalesReference();
        result.setReportingMonth(calculator.month(input.reportingMonth()));
        result.setCategory(calculator.category(input.categoryId(), previous == null ? null : previous.getCategory()));
        result.setSoldLastMonthCount(calculator.count(input.soldLastMonthCount(), true));
        return result;
    }

    private SubmissionResult retry(ElectricalTestingSalesReference saved, SalesReferenceRequest input, AppUser actor) {
        if (saved.getDeletedAt() != null || !saved.getCreatedBy().getId().equals(actor.getId())
                || !saved.getReportingMonth().equals(calculator.month(input.reportingMonth()))
                || !saved.getCategory().getId().equals(input.categoryId())
                || BigDecimal.valueOf(saved.getSoldLastMonthCount()).compareTo(input.soldLastMonthCount()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This submission ID has already been used. Check saved records before starting another submission.");
        }
        return new SubmissionResult(response(saved), false);
    }

    private ElectricalTestingSalesReference active(Long id) {
        return records.findByIdAndDeletedAtIsNull(id).orElseThrow(ElectricalTestingSalesReferenceService::notFound);
    }

    private AppUser actor(String username) {
        return users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private SalesReferenceResponse response(ElectricalTestingSalesReference record) {
        return new SalesReferenceResponse(record.getId(),record.getVersion(),record.getSubmissionId(),java.time.YearMonth.from(record.getReportingMonth()).toString(), record.getCategory().getId(), record.getCategory().getName(), record.getSoldLastMonthCount(),
                record.getCreatedBy() == null ? null : record.getCreatedBy().getUsername(),record.getCreatedAt(),
                record.getUpdatedBy() == null ? null : record.getUpdatedBy().getUsername(),record.getUpdatedAt());
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found.");
    }

    public record SubmissionResult(SalesReferenceResponse record, boolean created) {}
}
