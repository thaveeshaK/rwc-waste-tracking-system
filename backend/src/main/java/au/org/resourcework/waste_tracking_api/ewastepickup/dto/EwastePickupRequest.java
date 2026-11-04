package au.org.resourcework.waste_tracking_api.ewastepickup.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
public record EwastePickupRequest(UUID submissionId,@NotNull LocalDate recordDate,
 @NotBlank @Size(max=255) String organisation,
 @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=8,fraction=2) BigDecimal enteredWeight,
 @NotNull List<@NotNull @Valid ContainerInput> containers,@PositiveOrZero Long version) {
 public record ContainerInput(@NotNull @Positive Long containerTypeId,@NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer=10,fraction=0) BigDecimal quantity) {}
}
