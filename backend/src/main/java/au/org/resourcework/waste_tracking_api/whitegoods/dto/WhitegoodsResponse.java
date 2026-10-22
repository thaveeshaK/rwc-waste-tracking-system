package au.org.resourcework.waste_tracking_api.whitegoods.dto;
import java.time.*;
import java.util.UUID;
public record WhitegoodsResponse(Long id, Long version, UUID submissionId, LocalDate recordDate,
        Integer count, String kgPerItem, String estimatedWeight,
        String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {}
