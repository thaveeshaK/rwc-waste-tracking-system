package au.org.resourcework.waste_tracking_api.pickup;

import java.math.*;
import java.time.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PickupCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private final Clock clock;

    public PickupCalculator(Clock clock) { this.clock = clock; }

    public void validateDate(LocalDate date) {
        if (date.isBefore(LocalDate.of(1, 1, 1)) || date.isAfter(LocalDate.now(clock))) {
            invalid("Record date must be a past or current date in Australia/Hobart.");
        }
    }

    public void validate(LocalDate date, BigDecimal netWeight, List<BigDecimal> percentages) {
        validateDate(date);
        decimal(netWeight, new BigDecimal("99999999.99"), "Pickup weight");
        if (netWeight.signum() == 0) invalid("Pickup weight must be greater than 0 kg.");
        BigDecimal total = BigDecimal.ZERO;
        for (var percentage : percentages) {
            decimal(percentage, HUNDRED, "Pickup percentage");
            total = total.add(percentage);
        }
        if (total.compareTo(HUNDRED) != 0) invalid("Pickup percentages must total exactly 100.00%.");
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
