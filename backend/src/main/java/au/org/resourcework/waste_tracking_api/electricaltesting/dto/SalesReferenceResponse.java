package au.org.resourcework.waste_tracking_api.electricaltesting.dto;
import au.org.resourcework.waste_tracking_api.electricaltesting.TallyType;
import java.time.*;
import java.util.UUID;
public record SalesReferenceResponse(Long id, Long version, UUID submissionId, String reportingMonth, Long categoryId, String categoryName, Integer soldLastMonthCount,
        String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {}
