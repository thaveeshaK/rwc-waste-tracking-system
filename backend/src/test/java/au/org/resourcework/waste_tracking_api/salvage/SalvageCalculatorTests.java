package au.org.resourcework.waste_tracking_api.salvage;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SalvageCalculatorTests {
    private final SalvageCalculator calculator = new SalvageCalculator(
            Clock.fixed(Instant.parse("2026-10-21T14:00:00Z"), ZoneId.of("Australia/Hobart")));

    @Test
    void validatesDateInHobart() {
        calculator.validateDate(LocalDate.parse("2026-10-22"));
        calculator.validateDate(LocalDate.parse("2020-01-01"));
        assertThatThrownBy(() -> calculator.validateDate(LocalDate.parse("2026-10-23"))).hasMessageContaining("Australia/Hobart");
    }

    @Test
    void zeroWeightDoesNotRequirePercentagesOrOneHundredPercent() {
        for (var direction : SalvageDirection.values()) {
            calculator.validateDirection(new BigDecimal("0.00"), List.of(), direction);
            calculator.validateDirection(BigDecimal.ZERO, List.of(new BigDecimal("30")), direction);
        }
    }

    @Test
    void validatesTotalsIndependentlyForPositiveWeights() {
        for (var direction : SalvageDirection.values()) {
            calculator.validateDirection(new BigDecimal("10"), decimals("33.33", "66.67"), direction);
            for (var invalid : List.of(List.<BigDecimal>of(), decimals("99.99"), decimals("50", "50.01"), decimals("60"), decimals("40"))) {
                assertThatThrownBy(() -> calculator.validateDirection(new BigDecimal("10"), invalid, direction))
                        .hasMessageContaining("100.00%");
            }
        }
    }

    @Test
    void rejectsNegativeWeightsAndInvalidPrecisionOrRange() {
        for (var invalid : decimals("-0.01", "1.001", "100000000")) {
            assertThatThrownBy(() -> calculator.validateDirection(invalid, decimals("100"), SalvageDirection.UP))
                    .hasMessageContaining("weight");
        }
        for (var invalid : decimals("-1", "100.01", "50.001")) {
            assertThatThrownBy(() -> calculator.validateDirection(new BigDecimal("10"), List.of(invalid), SalvageDirection.DOWN))
                    .hasMessageContaining("percentage");
        }
    }

    @Test
    void usesCouncilWeightDirectlyAndRoundsHalfUpWithoutRedistribution() {
        assertThat(calculator.materialWeight(new BigDecimal("125.50"), new BigDecimal("20"))).isEqualByComparingTo("25.10");
        assertThat(calculator.materialWeight(new BigDecimal("0.01"), new BigDecimal("50"))).isEqualByComparingTo("0.01");
        assertThat(calculator.materialWeight(new BigDecimal("0.01"), new BigDecimal("50")).multiply(new BigDecimal("2")))
                .isEqualByComparingTo("0.02");
    }

    private List<BigDecimal> decimals(String... values) { return Arrays.stream(values).map(BigDecimal::new).toList(); }
}
