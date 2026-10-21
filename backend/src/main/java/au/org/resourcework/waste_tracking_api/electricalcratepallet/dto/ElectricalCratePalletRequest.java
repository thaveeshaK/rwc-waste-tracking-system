package au.org.resourcework.waste_tracking_api.electricalcratepallet.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ElectricalCratePalletRequest(
        UUID submissionId,
        @NotNull LocalDate recordDate,
        @NotNull @Positive Long containerTypeId,
        @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal grossWeight,
        @NotNull @Size(min = 2, max = 2) List<@NotNull @Valid MaterialInput> materials,
        @PositiveOrZero Long version) {
    public record MaterialInput(
            @NotNull @Positive Long materialCategoryId,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal percentage) {}
}
