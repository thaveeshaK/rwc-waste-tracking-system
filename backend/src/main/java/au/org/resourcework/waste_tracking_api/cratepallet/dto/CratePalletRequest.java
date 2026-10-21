package au.org.resourcework.waste_tracking_api.cratepallet.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CratePalletRequest(
        UUID submissionId,
        @NotNull LocalDate recordDate,
        @NotNull @Positive Long containerTypeId,
        @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal grossWeight,
        @NotEmpty List<@NotNull @Valid MaterialInput> materials) {
    public record MaterialInput(
            @NotNull @Positive Long materialCategoryId,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal percentage) {}
}
