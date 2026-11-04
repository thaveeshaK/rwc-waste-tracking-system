package au.org.resourcework.waste_tracking_api.scrap.dto;
import java.time.*;
import java.util.*;
public record ScrapResponse(Long id, Long version, UUID submissionId, String reportingMonth,
    String ironAndSteelWeight,
    String aluminiumWeight,
    String otherNonFerrousWeight,
    String mixedMetalsWeight,
    String ewasteWeight, String totalWeight,
    String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {}
