package au.org.resourcework.waste_tracking_api.electricaltesting.dto;
import au.org.resourcework.waste_tracking_api.electricaltesting.TallyType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
public record TallyRequest(UUID submissionId, @NotNull LocalDate recordDate, @Positive Long categoryId,
        @NotNull TallyType tallyType,
        @NotNull @DecimalMin(value="0", inclusive=false) @DecimalMax("2147483647") @Digits(integer=10, fraction=0) BigDecimal count,
        @PositiveOrZero Long version) {}
