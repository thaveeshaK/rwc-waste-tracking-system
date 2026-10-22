package au.org.resourcework.waste_tracking_api.whitegoods;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class WhitegoodsCalculatorTests {
    private final WhitegoodsCalculator calculator = new WhitegoodsCalculator(Clock.fixed(Instant.parse("2026-10-31T14:00:00Z"), ZoneId.of("Australia/Hobart")));
    @Test void validatesZeroWholeCountsAndRejectsFractionalNegativeAndOverflow() {
        var date = LocalDate.of(2026, 11, 1);
        assertThat(calculator.validate(date, BigDecimal.ZERO)).isZero();
        assertThat(calculator.validate(date, new BigDecimal("2147483647"))).isEqualTo(Integer.MAX_VALUE);
        for (String n : List.of("-1", "0.5", "2147483648")) assertThatThrownBy(() -> calculator.validate(date, new BigDecimal(n))).hasMessageContaining("whole number");
    }
    @Test void calculatesExactWeightIncludingLargeTotals() {
        assertThat(calculator.estimatedWeight(0)).isEqualByComparingTo("0.00");
        assertThat(calculator.estimatedWeight(15)).isEqualByComparingTo("720.00");
        assertThat(calculator.estimatedWeight(4294967294L)).isEqualByComparingTo("206158430112.00");
    }
    @Test void usesHobartDateAndMonthBoundary() {
        calculator.validate(LocalDate.of(2026, 11, 1), BigDecimal.ONE);
        assertThatThrownBy(() -> calculator.validate(LocalDate.of(2026, 11, 2), BigDecimal.ONE)).hasMessageContaining("Australia/Hobart");
        assertThat(calculator.month("2026-11")).isEqualTo(YearMonth.of(2026, 11));
        assertThatThrownBy(() -> calculator.month("2026-12")).hasMessageContaining("Australia/Hobart");
    }
    @Test void rejectsMalformedMonths() {
        for (String month : List.of("2024-13", "2024-2", "0000-01", "invalid")) assertThatThrownBy(() -> calculator.month(month)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
