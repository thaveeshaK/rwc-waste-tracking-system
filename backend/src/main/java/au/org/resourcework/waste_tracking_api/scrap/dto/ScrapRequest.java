package au.org.resourcework.waste_tracking_api.scrap.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
public record ScrapRequest(UUID submissionId, @NotBlank String reportingMonth,
    @NotNull @DecimalMin("0") @Digits(integer=8, fraction=2) BigDecimal ironAndSteelWeight,
    @NotNull @DecimalMin("0") @Digits(integer=8, fraction=2) BigDecimal aluminiumWeight,
    @NotNull @DecimalMin("0") @Digits(integer=8, fraction=2) BigDecimal otherNonFerrousWeight,
    @NotNull @DecimalMin("0") @Digits(integer=8, fraction=2) BigDecimal mixedMetalsWeight,
    @NotNull @DecimalMin("0") @Digits(integer=8, fraction=2) BigDecimal ewasteWeight, @PositiveOrZero Long version) {}
