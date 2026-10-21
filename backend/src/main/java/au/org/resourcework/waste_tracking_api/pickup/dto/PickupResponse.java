package au.org.resourcework.waste_tracking_api.pickup.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Decimal values are strings so the browser never needs binary floating-point calculations. */
public record PickupResponse(
        Long id, Long version, UUID submissionId, LocalDate recordDate,
        String pickupLocation, String netWeight,
        List<MaterialResponse> materials,
        String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
    public record MaterialResponse(Long materialCategoryId, String name, String percentage, String materialWeight) {}
}
