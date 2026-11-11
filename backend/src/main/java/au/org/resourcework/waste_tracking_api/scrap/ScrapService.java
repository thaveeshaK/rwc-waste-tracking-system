package au.org.resourcework.waste_tracking_api.scrap;

import au.org.resourcework.waste_tracking_api.auth.*;
import au.org.resourcework.waste_tracking_api.scrap.dto.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class ScrapService {
    private final ScrapRepository records;
    private final AppUserRepository users;
    private final Clock clock;

    public ScrapService(ScrapRepository records, AppUserRepository users, Clock clock) {
        this.records=records; this.users=users; this.clock=clock;
    }
    public ScrapPreview preview(ScrapRequest input) { return preview(input, null); }
    @PreAuthorize("hasRole('ADMIN')")
    public ScrapPreview previewEdit(Long id, ScrapRequest input) {
        var saved=active(id); checkVersion(input,saved); return preview(input,saved);
    }
    private ScrapPreview preview(ScrapRequest input, ScrapRecord previous) {
        var calculated=calculate(input,null);
        if(previous!=null) calculated.setVersion(previous.getVersion());
        var current=totals(month(input.reportingMonth()),previous==null?null:previous.getId());
        var projected=new ArrayList<BigDecimal>();
        var amounts=amounts(calculated);
        for(int i=0;i<5;i++) projected.add(current.get(i).add(amounts.get(i)));
        return new ScrapPreview(response(calculated),totalsResponse(input.reportingMonth(),current),totalsResponse(input.reportingMonth(),projected));
    }
    @Transactional
    public SubmissionResult submit(ScrapRequest input, String username) {
        if (input.submissionId() == null) badRequest("A submission ID is required.");
        var actor = actor(username);
        var existing = records.findBySubmissionId(input.submissionId());
        if (existing.isPresent()) return retry(existing.get(), input, actor);

        var calculated = calculate(input, null);
        int inserted = records.insertIfAbsent(input.submissionId(), calculated.getReportingMonth(),
                calculated.getIronAndSteelWeight(), calculated.getAluminiumWeight(), calculated.getOtherNonFerrousWeight(), calculated.getMixedMetalsWeight(), calculated.getEwasteWeight(), actor.getId(), clock.instant());
        var saved = records.findBySubmissionId(input.submissionId()).orElseThrow();
        if (inserted == 0) return retry(saved, input, actor);
        records.flush();
        return new SubmissionResult(response(saved), true);
    }

    public List<ScrapResponse> list(LocalDate from, LocalDate to) {
        var start = from == null ? LocalDate.of(1, 1, 1) : from;
        var end = to == null ? LocalDate.of(9998,12,31) : to;
        if (start.isAfter(end)) badRequest("Start date must not be after end date.");
        return records.findByDeletedAtIsNullAndReportingMonthBetweenOrderByReportingMonthDescIdDesc(start.withDayOfMonth(1), end.withDayOfMonth(1)).stream().map(this::response).toList();
    }

    public ScrapResponse get(Long id) { return response(active(id)); }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ScrapResponse update(Long id, ScrapRequest input, String username) {
        var saved = active(id);
        checkVersion(input, saved);
        var calculated = calculate(input, saved);
        saved.setReportingMonth(calculated.getReportingMonth());
        saved.setIronAndSteelWeight(calculated.getIronAndSteelWeight());
        saved.setAluminiumWeight(calculated.getAluminiumWeight());
        saved.setOtherNonFerrousWeight(calculated.getOtherNonFerrousWeight());
        saved.setMixedMetalsWeight(calculated.getMixedMetalsWeight());
        saved.setEwasteWeight(calculated.getEwasteWeight());
        saved.setUpdatedBy(actor(username));
        saved.setUpdatedAt(clock.instant());
        records.flush();
        return response(saved);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id, String username) {
        var saved = records.lockActiveById(id).orElseThrow(ScrapService::notFound);
        var actor = actor(username);
        var now = clock.instant();
        saved.setDeletedBy(actor);
        saved.setDeletedAt(now);
        saved.setUpdatedBy(actor);
        saved.setUpdatedAt(now);
    }

    private ScrapRecord calculate(ScrapRequest input, ScrapRecord previous) {
        var reportingMonth=month(input.reportingMonth());
        if(reportingMonth.isAfter(YearMonth.now(clock))) badRequest("Reporting month cannot be in the future (Australia/Hobart).");
        var result=new ScrapRecord(); result.setReportingMonth(reportingMonth.atDay(1));
        result.setIronAndSteelWeight(input.ironAndSteelWeight().setScale(2));
        result.setAluminiumWeight(input.aluminiumWeight().setScale(2));
        result.setOtherNonFerrousWeight(input.otherNonFerrousWeight().setScale(2));
        result.setMixedMetalsWeight(input.mixedMetalsWeight().setScale(2));
        result.setEwasteWeight(input.ewasteWeight().setScale(2));
        if(amounts(result).stream().anyMatch(v->v.signum()<0 || v.compareTo(new BigDecimal("99999999.99"))>0)) badRequest("Weights must be non-negative with at most two decimals.");
        if(amounts(result).stream().noneMatch(v->v.signum()>0)) badRequest("At least one material weight must be greater than zero.");
        return result;
    }
    private List<BigDecimal> amounts(ScrapRecord r) { return List.of(r.getIronAndSteelWeight(), r.getAluminiumWeight(), r.getOtherNonFerrousWeight(), r.getMixedMetalsWeight(), r.getEwasteWeight()); }
    private SubmissionResult retry(ScrapRecord saved, ScrapRequest input, AppUser actor) {
        var requested=calculate(input,null);
        if(saved.getDeletedAt()!=null || !saved.getCreatedBy().getId().equals(actor.getId()) || !saved.getReportingMonth().equals(requested.getReportingMonth()) || !amounts(saved).equals(amounts(requested)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This submission ID has already been used. View Records before starting a new submission.");
        return new SubmissionResult(response(saved),false);
    }
    private YearMonth month(String value) {
        try { if(value==null || !value.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
            var month=YearMonth.parse(value); if(month.getYear()<1 || month.getYear()>9998) throw new IllegalArgumentException(); return month;
        } catch(RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a valid reporting month (YYYY-MM)."); }
    }
    private List<BigDecimal> totals(YearMonth month,Long excluded) {
        var t=records.totals(month.atDay(1),excluded);
        return List.of(t.getIronAndSteelWeight(), t.getAluminiumWeight(), t.getOtherNonFerrousWeight(), t.getMixedMetalsWeight(), t.getEwasteWeight());
    }
    private ScrapTotals totalsResponse(String month,List<BigDecimal> a) {
        return new ScrapTotals(month,a.get(0).setScale(2).toPlainString(),a.get(1).setScale(2).toPlainString(),a.get(2).setScale(2).toPlainString(),a.get(3).setScale(2).toPlainString(),a.get(4).setScale(2).toPlainString(),a.stream().reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2).toPlainString());
    }
    public ScrapTotals monthlySummary(String value) { return totalsResponse(value,totals(month(value),null)); }

    private ScrapRecord active(Long id) {
        return records.findByIdAndDeletedAtIsNull(id).orElseThrow(ScrapService::notFound);
    }

    private AppUser actor(String username) {
        return users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private ScrapResponse response(ScrapRecord record) {
        return new ScrapResponse(record.getId(),record.getVersion(),record.getSubmissionId(),YearMonth.from(record.getReportingMonth()).toString(),
                record.getIronAndSteelWeight().toPlainString(), record.getAluminiumWeight().toPlainString(), record.getOtherNonFerrousWeight().toPlainString(), record.getMixedMetalsWeight().toPlainString(), record.getEwasteWeight().toPlainString(),amounts(record).stream().reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2).toPlainString(),
                record.getCreatedBy()==null?null:record.getCreatedBy().getUsername(),record.getCreatedAt(),record.getUpdatedBy()==null?null:record.getUpdatedBy().getUsername(),record.getUpdatedAt());
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static void checkVersion(ScrapRequest input, ScrapRecord saved) {
        if (input.version() == null) badRequest("The record version is required for a correction.");
        if (!saved.getVersion().equals(input.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This record has changed. Reload it from Records before correcting it.");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found.");
    }

    public record SubmissionResult(ScrapResponse record, boolean created) {}
}
