package au.org.resourcework.waste_tracking_api.electricaltesting.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
public record SalesReferenceRequest(UUID submissionId, @NotBlank String reportingMonth, @NotNull @Positive Long categoryId,
        @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer=10, fraction=0) BigDecimal soldLastMonthCount,
        @PositiveOrZero Long version) {}
