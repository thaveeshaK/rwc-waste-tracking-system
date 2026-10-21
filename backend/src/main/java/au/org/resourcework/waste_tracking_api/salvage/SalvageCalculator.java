package au.org.resourcework.waste_tracking_api.salvage;

import java.math.*;
import java.time.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SalvageCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private final Clock clock;

    public SalvageCalculator(Clock clock) { this.clock = clock; }

    public void validateDate(LocalDate date) {
        if (date.isBefore(LocalDate.of(1, 1, 1)) || date.isAfter(LocalDate.now(clock))) {
            invalid("Record date must be a past or current date in Australia/Hobart.");
        }
    }

    public void validateDirection(BigDecimal kg, List<BigDecimal> percentages, SalvageDirection direction) {
        decimal(kg, new BigDecimal("99999999.99"), direction + " weight");
        // Zero-weight directions have no persisted breakdown, regardless of stale form percentages.
        if (kg.signum() == 0) return;
        BigDecimal total = BigDecimal.ZERO;
        for (var percentage : percentages) {
            decimal(percentage, HUNDRED, direction + " percentage");
            total = total.add(percentage);
        }
        if (total.compareTo(HUNDRED) != 0) invalid(direction + " percentages must total exactly 100.00% for a positive weight.");
    }

    public BigDecimal materialWeight(BigDecimal kg, BigDecimal percentage) {
        return kg.multiply(percentage).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private void decimal(BigDecimal value, BigDecimal max, String field) {
        if (value == null || value.signum() < 0 || value.scale() > 2 || value.compareTo(max) > 0) {
            invalid(field + " must be nonnegative, within range, and have at most two decimal places.");
        }
    }

    private static void invalid(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
