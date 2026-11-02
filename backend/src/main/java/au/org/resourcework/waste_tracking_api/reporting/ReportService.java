package au.org.resourcework.waste_tracking_api.reporting;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static au.org.resourcework.waste_tracking_api.reporting.ReportData.*;

@Service
@Transactional(readOnly=true)
@PreAuthorize("hasRole('ADMIN')")
public class ReportService {
    public static final String NOTE="Monthly working aid only; not a completed Waste Levy return, NRE reconciliation or certified report. E-waste Pickup, Scrap and legacy E-waste reconciliation/carry-over calculations are excluded.";
    private static final Set<String> WORKFLOWS=Set.of("CRATE_PALLET","SALVAGE","PICKUP","ELECTRICAL_CRATE_PALLET","COLLECTABLES","WHITEGOODS","ELECTRICAL_TESTING");
    private final ReportingRepository repository;
    private final Clock clock;
    private final au.org.resourcework.waste_tracking_api.settings.OperationalConfiguration configuration;
    public ReportService(ReportingRepository repository, Clock clock, au.org.resourcework.waste_tracking_api.settings.OperationalConfiguration configuration) { this.repository=repository; this.clock=clock; this.configuration=configuration; }

    public ReportFilter validate(ReportFilter input) {
        LocalDate today=LocalDate.now(clock);
        LocalDate from=input.from();
        if (from==null) {
            var defaults=configuration.current();
            int startMonth=defaults.financialYearStartMonth();
            from=defaults.reportPeriod().equals("CURRENT_FINANCIAL_YEAR")?LocalDate.of(today.getYear()-(today.getMonthValue()<startMonth?1:0),startMonth,1):today.withDayOfMonth(1);
        }
        LocalDate to=input.to()==null?today:input.to();
        if (from.getYear()<1 || to.getYear()>9998 || from.isAfter(to)) bad("Invalid reporting date range.");
        String workflow=blank(input.workflow()), location=blank(input.location()), direction=blank(input.direction());
        if (workflow!=null && !WORKFLOWS.contains(workflow)) bad("Unknown workflow.");
        if (location!=null && (!"PICKUP".equals(workflow) || location.length()>255)) bad("Location filtering requires the Pickup workflow and at most 255 characters.");
        if (direction!=null && (!"SALVAGE".equals(workflow) || !Set.of("UP","DOWN").contains(direction))) bad("Direction filtering requires Salvage Up or Down.");
        if (input.materialCategoryId()!=null && (input.materialCategoryId()<1 || "WHITEGOODS".equals(workflow) || "ELECTRICAL_TESTING".equals(workflow))) bad("Material category is incompatible with this workflow.");
        if (input.testingCategoryId()!=null && (input.testingCategoryId()<1 || !"ELECTRICAL_TESTING".equals(workflow) || input.materialCategoryId()!=null)) bad("Testing category filtering requires Electrical Testing.");
        if (input.materialCategoryId()!=null && repository.options().materials().stream().noneMatch(c->c.id().equals(input.materialCategoryId()))) bad("Unknown material category.");
        if (input.testingCategoryId()!=null && repository.options().testingCategories().stream().noneMatch(c->c.id().equals(input.testingCategoryId()))) bad("Unknown testing category.");
        return new ReportFilter(from,to,workflow,input.materialCategoryId(),location,direction,input.testingCategoryId());
    }
    public Result summary(ReportFilter input) {
        var f=validate(input); return new Result(f,"Australia/Hobart",clock.instant(),repository.totals(f));
    }
    public Details details(ReportFilter input, int page, int size) {
        if (page<0 || page>1000000 || size<1 || size>200) bad("Invalid report page or size (maximum 200).");
        var f=validate(input);
        return new Details(f,"Australia/Hobart",clock.instant(),repository.rows(f,size,page*size),repository.rowCount(f),page,size);
    }
    public List<Row> exportRows(ReportFilter input) {
        var f=validate(input);
        if (repository.rowCount(f)>100000) bad("Export exceeds 100,000 rows. Narrow the date range or filters.");
        return repository.rows(f,null,0);
    }
    public Options options() { return repository.options(); }
    public Monthly monthly(String value) {
        YearMonth month;
        try { if (value==null || !value.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException(); month=YearMonth.parse(value); }
        catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a valid reporting month (YYYY-MM)."); }
        if (month.getYear()<1 || month.getYear()>9998) bad("Invalid reporting month.");
        var f=validate(new ReportFilter(month.atDay(1),month.atEndOfMonth(),null,null,null,null,null));
        var totals=new ArrayList<>(repository.totals(f).stream().filter(t->!t.workflow().equals("ELECTRICAL_TESTING")).toList());
        // Only the approved normal intake category totals are combined. Never combine electrical or Salvage.
        totals.addAll(generalIntake(totals));
        // Zero-fill category rows using historical applicability, without inventing cross-workflow mappings.
        addMissingCategories(totals);
        if (totals.stream().noneMatch(t->t.workflow().equals("WHITEGOODS"))) {
            totals.add(new Total("WHITEGOODS",null,null,null,null,"Whitegoods count","count","0"));
            totals.add(new Total("WHITEGOODS",null,null,null,null,"Estimated weight","kg","0.00"));
        }
        for (String direction:List.of("UP","DOWN")) if (totals.stream().noneMatch(t->t.workflow().equals("SALVAGE") && Objects.equals(t.direction(),direction) && t.metric().equals("Net weight"))) {
            totals.add(new Total("SALVAGE",direction,null,null,null,"Net weight","kg","0.00"));
        }
        return new Monthly(value,"Australia/Hobart",clock.instant(),NOTE,totals);
    }
    private void addMissingCategories(List<Total> totals) {
        for (var t:repository.categoryTemplates()) {
            boolean exists=totals.stream().anyMatch(v->v.workflow().equals(t.workflow()) && Objects.equals(v.direction(),t.direction()) && Objects.equals(v.categoryId(),t.categoryId()) && v.metric().equals("Material weight"));
            if (!exists) totals.add(t);
        }
        totals.sort(Comparator.comparing(Total::workflow).thenComparing(t->Objects.toString(t.direction(),"")).thenComparing(t->Objects.toString(t.container(),"")).thenComparing(t->t.categoryId()==null?0L:t.categoryId()).thenComparing(Total::metric));
    }
    private static final List<String> DASHBOARD_WORKFLOWS=List.of("CRATE_PALLET","SALVAGE","PICKUP","ELECTRICAL_CRATE_PALLET","COLLECTABLES","WHITEGOODS","ELECTRICAL_TESTING");
    private static final List<String> TESTING_STATUSES=List.of("UNTESTED_BROKEN_FAILED","TESTED_AND_PRICED","STOCK_CULL","DIVERTED_TO_OLS","DIVERTED_TO_COLL");

    public Overview overview() { return overview(null); }

    public Overview overview(String value) {
        LocalDate today=LocalDate.now(clock);
        YearMonth selected=YearMonth.now(clock);
        if (value!=null) {
            try {
                if (!value.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
                selected=YearMonth.parse(value);
            } catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a valid dashboard month (YYYY-MM)."); }
            if (selected.getYear()<1 || selected.isAfter(YearMonth.now(clock))) bad("Dashboard month must be past or current.");
        }
        LocalDate month=selected.atDay(1);
        LocalDate end=selected.equals(YearMonth.now(clock))?today:selected.atEndOfMonth();
        var filter=new ReportFilter(month,end,null,null,null,null,null);
        var monthly=summary(filter);
        var totals=monthly.totals();
        var rawActivity=repository.activity(filter);
        var activity=DASHBOARD_WORKFLOWS.stream().map(w->new Activity(w,rawActivity.stream().filter(a->a.workflow().equals(w)).mapToLong(Activity::count).sum())).toList();
        var rawTrend=repository.trend(new ReportFilter(month.minusMonths(5),end,null,null,null,null,null));
        var trend=new ArrayList<Trend>();
        for(int offset=5;offset>=0;offset--) {
            String period=selected.minusMonths(offset).toString();
            for(String workflow:DASHBOARD_WORKFLOWS) trend.add(new Trend(period,workflow,rawTrend.stream()
                    .filter(t->t.month().equals(period) && t.workflow().equals(workflow)).mapToLong(Trend::count).sum()));
        }
        var metrics=new DashboardMetrics(metric(totals,"SALVAGE","Net weight","DOWN").setScale(2).toPlainString(),
                metric(totals,"SALVAGE","Net weight","UP").setScale(2).toPlainString(),
                metric(totals,"WHITEGOODS","Whitegoods count",null).toBigIntegerExact().toString(),
                metric(totals,"WHITEGOODS","Estimated weight",null).setScale(2).toPlainString());
        var testing=TESTING_STATUSES.stream().map(status->new Counter(status,
                metric(totals,"ELECTRICAL_TESTING",status,null).toBigIntegerExact().toString())).toList();
        return new Overview(today.toString(),selected.toString(),repository.submitted(today.atStartOfDay(clock.getZone()).toInstant(),today.plusDays(1).atStartOfDay(clock.getZone()).toInstant()),
                activity.stream().mapToLong(Activity::count).sum(),activity,repository.recent(5),trend,monthly,metrics,generalIntake(totals),testing,topIntake(totals),recoveredTrend(month,end));
    }

    private List<RecoveredTrend> recoveredTrend(LocalDate month, LocalDate end) {
        var raw=repository.recoveredTrend(new ReportFilter(month.minusMonths(5),end,null,null,null,null,null));
        var result=new ArrayList<RecoveredTrend>();
        for(int offset=5;offset>=0;offset--) {
            String period=YearMonth.from(month.minusMonths(offset)).toString();
            result.add(new RecoveredTrend(period,raw.stream().filter(t->t.month().equals(period)).map(t->new BigDecimal(t.kg()))
                    .reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2).toPlainString()));
        }
        return result;
    }
    /** Dashboard-only receiving/recovery view; not a unique-material or reporting total. */
    private List<Total> topIntake(List<Total> totals) {
        var combined=new TreeMap<Long,BigDecimal>();
        var names=new HashMap<Long,String>();
        for(var t:totals) if(t.metric().equals("Material weight") &&
                (Set.of("CRATE_PALLET","COLLECTABLES","PICKUP").contains(t.workflow()) ||
                 (t.workflow().equals("SALVAGE") && "DOWN".equals(t.direction())))) {
            combined.merge(t.categoryId(),new BigDecimal(t.value()),BigDecimal::add);
            names.put(t.categoryId(),t.category());
        }
        return combined.entrySet().stream().filter(e->e.getValue().signum()>0)
                .sorted(Map.Entry.<Long,BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).limit(8)
                .map(e->new Total("INTAKE_RECOVERY",null,null,e.getKey(),names.get(e.getKey()),"Material weight","kg",e.getValue().setScale(2).toPlainString())).toList();
    }

    private BigDecimal metric(List<Total> totals,String workflow,String metric,String direction) {
        return totals.stream().filter(t->t.workflow().equals(workflow) && t.metric().equals(metric) && Objects.equals(t.direction(),direction))
                .map(t->new BigDecimal(t.value())).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    /** Shared with the monthly working aid: only these three normal intake workflows are combined. */
    private List<Total> generalIntake(List<Total> totals) {
        var combined=new TreeMap<Long,BigDecimal>();
        var names=new HashMap<Long,String>();
        for (var t:totals) if (Set.of("CRATE_PALLET","PICKUP","COLLECTABLES").contains(t.workflow()) && t.metric().equals("Material weight")) {
            combined.merge(t.categoryId(),new BigDecimal(t.value()),BigDecimal::add); names.put(t.categoryId(),t.category());
        }
        return combined.entrySet().stream().map(e->new Total("GENERAL_INTAKE",null,null,e.getKey(),names.get(e.getKey()),"Material weight","kg",e.getValue().setScale(2).toPlainString())).toList();
    }
    private static String blank(String s) { return s==null || s.isBlank()?null:s.trim(); }
    private static void bad(String s) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,s); }
}
