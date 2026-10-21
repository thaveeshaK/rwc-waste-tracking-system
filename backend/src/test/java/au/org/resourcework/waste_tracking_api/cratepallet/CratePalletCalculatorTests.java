package au.org.resourcework.waste_tracking_api.cratepallet;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class CratePalletCalculatorTests {
    // It is already 22 October in Hobart, while UTC is still 21 October.
    private final CratePalletCalculator calculator = new CratePalletCalculator(
            Clock.fixed(Instant.parse("2026-10-21T14:00:00Z"), ZoneId.of("Australia/Hobart")));

    @Test
    void usesHobartDateAndAllowsTodayAndPastDates() {
        assertThat(net("2026-10-22", "160.00", "60.00", "100.00")).isEqualByComparingTo("100.00");
        assertThat(net("2020-01-01", "60.00", "60.00", "100.00")).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> net("2026-10-23", "160", "60", "100"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("future");
    }

    @Test
    void requiresExactlyOneHundredPercent() {
        assertThat(net("2026-10-22", "160", "60", "33.33", "33.33", "33.34")).isEqualByComparingTo("100");
        assertThatThrownBy(() -> net("2026-10-22", "160", "60", "99.99"))
                .hasMessageContaining("exactly 100.00");
        assertThatThrownBy(() -> net("2026-10-22", "160", "60", "100.01"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsInvalidPrecisionRangeAndNegativeNet() {
        assertThatThrownBy(() -> net("2026-10-22", "59.99", "60", "100")).hasMessageContaining("tare");
        assertThatThrownBy(() -> net("2026-10-22", "-1", "60", "100")).hasMessageContaining("Gross weight");
        assertThatThrownBy(() -> net("2026-10-22", "160.001", "60", "100")).hasMessageContaining("decimal");
        assertThatThrownBy(() -> net("2026-10-22", "100000000", "60", "100")).hasMessageContaining("range");
        assertThatThrownBy(() -> net("2026-10-22", "160", "60", "50.001", "49.999"))
                .hasMessageContaining("decimal");
        assertThatThrownBy(() -> net("2026-10-22", "160", "60", "-1", "101"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void roundsHalfUpWithoutRedistributingRemainders() {
        assertThat(calculator.materialWeight(new BigDecimal("0.01"), new BigDecimal("50.00")))
                .isEqualByComparingTo("0.01");
        assertThat(calculator.materialWeight(new BigDecimal("0.01"), new BigDecimal("50.00"))
                .multiply(new BigDecimal("2"))).isEqualByComparingTo("0.02");
        assertThat(calculator.materialWeight(new BigDecimal("123.45"), new BigDecimal("25.00")))
                .isEqualByComparingTo("30.86");
    }

    private BigDecimal net(String date, String gross, String tare, String... percentages) {
        return calculator.net(LocalDate.parse(date), new BigDecimal(gross), new BigDecimal(tare),
                List.of(percentages).stream().map(BigDecimal::new).toList());
    }
}
