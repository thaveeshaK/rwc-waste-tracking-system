package au.org.resourcework.waste_tracking_api.cratepallet.dto;

import au.org.resourcework.waste_tracking_api.records.RecordStatus;
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
        @NotNull List<@NotNull @Valid MaterialInput> materials,
        @PositiveOrZero Long version, RecordStatus status) {
    public CratePalletRequest {
        status = status == null ? RecordStatus.COMPLETED : status;
    }
    public CratePalletRequest(UUID submissionId, LocalDate recordDate, Long containerTypeId,
            BigDecimal grossWeight, List<MaterialInput> materials, Long version) {
        this(submissionId, recordDate, containerTypeId, grossWeight, materials, version, RecordStatus.COMPLETED);
    }
    public record MaterialInput(
            @NotNull @Positive Long materialCategoryId,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal percentage) {}
}
