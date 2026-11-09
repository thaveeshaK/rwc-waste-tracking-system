package au.org.resourcework.waste_tracking_api.electricaltesting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.math.BigDecimal;

/** A versioned reduction of one daily record, never a monthly aggregate. */
public record TallyReductionRequest(@NotNull LocalDate recordDate,
        @NotNull @PositiveOrZero Long version,
        @NotNull BigDecimal count) {}
