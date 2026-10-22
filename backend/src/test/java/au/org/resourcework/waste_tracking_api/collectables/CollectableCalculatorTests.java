package au.org.resourcework.waste_tracking_api.collectables;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CollectableCalculatorTests {
    private final CollectableCalculator calculator = new CollectableCalculator(Clock.fixed(Instant.parse("2026-10-21T14:00:00Z"), ZoneId.of("Australia/Hobart")));
    private BigDecimal net(String date, String gross, String... percentages) {
        return calculator.net(LocalDate.parse(date), new BigDecimal(gross), java.util.Arrays.stream(percentages).map(BigDecimal::new).toList());
    }
    @Test void rejectsEqualAndBelowTareButAcceptsPositiveNet() {
        for (String gross : List.of("2", "1.99", "0", "-1")) assertThatThrownBy(() -> net("2026-10-22", gross, "100", "0")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(net("2026-10-22", "2.01", "100", "0")).isEqualByComparingTo("0.01");
    }
    @Test void usesHobartDateBoundary() {
        net("2026-10-22", "150", "60", "40"); net("2020-01-01", "150", "60", "40");
        assertThatThrownBy(() -> net("2026-10-23", "150", "60", "40")).hasMessageContaining("Australia/Hobart");
    }
    @Test void validatesTotalsAndPrecision() {
        for (String pct : List.of("99.99", "100.01", "-1", "50.001")) assertThatThrownBy(() -> net("2026-10-22", "150", pct, "0")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        for (String gross : List.of("150.001", "100000000")) assertThatThrownBy(() -> net("2026-10-22", gross, "50", "50")).hasMessageContaining("decimal");
    }
    @Test void roundsHalfUpWithoutRedistribution() {
        assertThat(calculator.materialWeight(new BigDecimal("0.01"), new BigDecimal("50"))).isEqualByComparingTo("0.01");
        assertThat(calculator.materialWeight(new BigDecimal("125.50"), new BigDecimal("20"))).isEqualByComparingTo("25.10");
    }
}
