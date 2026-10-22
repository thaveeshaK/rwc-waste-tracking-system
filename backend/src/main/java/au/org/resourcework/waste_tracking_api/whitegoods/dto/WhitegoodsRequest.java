package au.org.resourcework.waste_tracking_api.whitegoods.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record WhitegoodsRequest(UUID submissionId, @NotNull LocalDate recordDate,
        @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0) BigDecimal count,
        @PositiveOrZero Long version) {}
