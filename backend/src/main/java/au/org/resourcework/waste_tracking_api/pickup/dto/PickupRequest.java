package au.org.resourcework.waste_tracking_api.pickup.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PickupRequest(
        UUID submissionId,
        @NotNull LocalDate recordDate,
        @NotBlank @Size(max = 255) String pickupLocation,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal netWeight,
        @NotEmpty List<@NotNull @Valid MaterialInput> materials,
        @PositiveOrZero Long version) {
    public record MaterialInput(
            @NotNull @Positive Long materialCategoryId,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal percentage) {}
}
