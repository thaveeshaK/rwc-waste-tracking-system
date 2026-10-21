package au.org.resourcework.waste_tracking_api.salvage.dto;

import java.time.*;
import java.util.*;

public record SalvageResponse(
        Long id, Long version, UUID submissionId, LocalDate recordDate, String crew,
        DirectionResponse up, DirectionResponse down,
        String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
    public record DirectionResponse(LocalTime time, String kg, List<MaterialResponse> materials) {}
    public record MaterialResponse(Long materialCategoryId, String name, String percentage, String materialWeight) {}
}
