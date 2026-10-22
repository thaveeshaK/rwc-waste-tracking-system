package au.org.resourcework.waste_tracking_api.electricaltesting.dto;
import au.org.resourcework.waste_tracking_api.electricaltesting.TallyType;
import java.time.*;
import java.util.UUID;
public record TallyResponse(Long id, Long version, UUID submissionId, LocalDate recordDate, Long categoryId, String categoryName, TallyType tallyType, Integer count,
        String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {}
