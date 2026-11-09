package au.org.resourcework.waste_tracking_api.settings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import jakarta.validation.constraints.*;

public final class SettingsData {
    private SettingsData() {}
    public record Configuration(@NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max=100) String organisationName, @NotNull @Size(max=100) String siteName,
            @NotBlank @Size(max=100) String reportDisplayName, @NotNull String displayUnit,
            @NotNull String reportPeriod, @Min(1) @Max(12) int financialYearStartMonth,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=3,fraction=2) BigDecimal whitegoodsKgPerItem,
            String updatedBy, Instant updatedAt) {}
    public record ReferenceInput(Long version, @NotBlank @Size(max=100) String name,
            @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal tareWeight,
            boolean active, @NotNull Set<String> contexts, String summaryGroup) {}
    public record Reference(Long id, Long version, String name, String tareWeight, boolean active,
            Set<String> contexts, boolean restricted, String summaryGroup) {}
    public record Operational(String organisationName, String siteName, String reportDisplayName,
            String whitegoodsKgPerItem, String timezone) {}
}
