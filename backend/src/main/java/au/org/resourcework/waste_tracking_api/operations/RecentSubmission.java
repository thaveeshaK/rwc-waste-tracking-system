package au.org.resourcework.waste_tracking_api.operations;

import java.time.LocalDate;

public record RecentSubmission(long id, LocalDate recordDate, String workflow, String details,
        String weightKg, Long count) {}
