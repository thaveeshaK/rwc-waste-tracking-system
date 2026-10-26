package au.org.resourcework.waste_tracking_api.reporting;

import java.time.LocalDate;

/** Dates are inclusive operational dates. Category namespaces are intentionally separate. */
public record ReportFilter(LocalDate from, LocalDate to, String workflow, Long materialCategoryId,
        String location, String direction, Long testingCategoryId) {}
