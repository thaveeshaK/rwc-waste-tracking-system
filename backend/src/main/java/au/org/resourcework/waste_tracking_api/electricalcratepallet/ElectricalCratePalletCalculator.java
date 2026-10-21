package au.org.resourcework.waste_tracking_api.electricalcratepallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ElectricalCratePalletCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private final Clock clock;

    public ElectricalCratePalletCalculator(Clock clock) { this.clock = clock; }

    public BigDecimal net(LocalDate date, BigDecimal gross, BigDecimal tare, List<BigDecimal> percentages) {
        if (date.isAfter(LocalDate.now(clock))) invalid("Record date cannot be in the future (Australia/Hobart).");
        if (date.isBefore(LocalDate.of(1, 1, 1))) invalid("Record date must be a valid calendar date.");
        validateDecimal(gross, new BigDecimal("99999999.99"), "Gross weight");
        BigDecimal total = BigDecimal.ZERO;
        for (var percentage : percentages) {
            validateDecimal(percentage, HUNDRED, "Percentage");
            total = total.add(percentage);
        }
        if (total.compareTo(HUNDRED) != 0) invalid("Material percentages must total exactly 100.00%.");
        var net = gross.subtract(tare);
        if (net.signum() <= 0) invalid("Gross weight must be strictly greater than tare; net material weight must be greater than 0 kg.");
        return net.setScale(2, RoundingMode.UNNECESSARY);
    }

    public BigDecimal materialWeight(BigDecimal net, BigDecimal percentage) {
        return net.multiply(percentage).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private void validateDecimal(BigDecimal value, BigDecimal maximum, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(maximum) > 0 || value.scale() > 2) {
            invalid(label + " must be nonnegative, within range, and have at most two decimal places.");
        }
    }

    private static void invalid(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
