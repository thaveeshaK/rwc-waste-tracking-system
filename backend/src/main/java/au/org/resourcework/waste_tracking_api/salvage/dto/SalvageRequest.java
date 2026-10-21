package au.org.resourcework.waste_tracking_api.salvage.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public record SalvageRequest(
        UUID submissionId,
        @NotNull LocalDate recordDate,
        @NotBlank @Size(max = 255) String crew,
        @NotNull @Valid DirectionInput up,
        @NotNull @Valid DirectionInput down,
        @PositiveOrZero Long version) {
    public record DirectionInput(
            @NotNull LocalTime time,
            @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal kg,
            List<@NotNull @Valid MaterialInput> materials) {
        public List<MaterialInput> materialsOrEmpty() { return materials == null ? List.of() : materials; }
    }
    public record MaterialInput(
            @NotNull @Positive Long materialCategoryId,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal percentage) {}
}
