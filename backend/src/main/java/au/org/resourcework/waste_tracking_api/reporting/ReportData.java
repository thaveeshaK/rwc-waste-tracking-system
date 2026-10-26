package au.org.resourcework.waste_tracking_api.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ReportData {
    private ReportData() {}
    public record Row(String rowType, String workflow, Long id, LocalDate date, String container,
            String location, String crew, String direction, Long categoryId, String category,
            String status, String kg, String grossKg, String tareKg, String percentage, Long count,
            String factor, String timeUp, String timeDown, String submissionId, Long version, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {}
    public record Total(String workflow, String direction, String container, Long categoryId,
            String category, String metric, String unit, String value) {}
    public record Result(ReportFilter filters, String timezone, Instant generatedAt, List<Total> totals) {}
    public record Details(ReportFilter filters, String timezone, Instant generatedAt, List<Row> rows,
            long totalRows, int page, int size) {}
    public record Monthly(String month, String timezone, Instant generatedAt, String note, List<Total> totals) {}
    public record Activity(String workflow, long count) {}
    public record Trend(String month, String workflow, long count) {}
    public record Overview(String today, long submittedToday, long recordsThisMonth, List<Activity> activity,
            List<Row> recent, List<Trend> trend, Result monthly) {}
    public record Option(Long id, String name) {}
    public record Options(List<Option> materials, List<Option> testingCategories) {}
}
