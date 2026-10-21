package au.org.resourcework.waste_tracking_api.pickup;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PickupCalculatorTests {
    private final PickupCalculator calculator = new PickupCalculator(Clock.fixed(Instant.parse("2026-10-21T14:00:00Z"), ZoneId.of("Australia/Hobart")));
    private final LocalDate today = LocalDate.of(2026, 10, 22);
    @Test void validatesDateUsingHobart() {
        calculator.validateDate(today); calculator.validateDate(today.minusYears(1));
        assertThatThrownBy(() -> calculator.validateDate(today.plusDays(1))).hasMessageContaining("Australia/Hobart");
    }
    @Test void rejectsZeroNegativeAndInvalidPrecisionWeights() {
        for (String invalid : List.of("0", "-0.01", "1.001", "100000000")) {
            assertThatThrownBy(() -> calculator.validate(today, new BigDecimal(invalid), List.of(new BigDecimal("100")))).isInstanceOf(RuntimeException.class);
        }
        calculator.validate(today, new BigDecimal("0.01"), List.of(new BigDecimal("100")));
    }
    @Test void requiresExactTotalsAndValidPercentages() {
        for (String invalid : List.of("99.99", "100.01", "-1", "50.001")) {
            assertThatThrownBy(() -> calculator.validate(today, BigDecimal.ONE, List.of(new BigDecimal(invalid)))).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> calculator.validate(today, BigDecimal.ONE, List.of())).hasMessageContaining("100.00%");
        calculator.validate(today, BigDecimal.ONE, List.of(new BigDecimal("33.33"), new BigDecimal("66.67")));
    }
    @Test void usesFinalWeightAndHalfUpWithoutRedistribution() {
        assertThat(calculator.materialWeight(new BigDecimal("125.50"), new BigDecimal("20"))).isEqualByComparingTo("25.10");
        assertThat(calculator.materialWeight(new BigDecimal("0.01"), new BigDecimal("50"))).isEqualByComparingTo("0.01");
    }
}
