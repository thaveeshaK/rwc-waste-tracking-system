package au.org.resourcework.waste_tracking_api.whitegoods;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WhitegoodsCalculator {
    public static final BigDecimal KG_PER_ITEM = new BigDecimal("48.00");
    private final Clock clock;
    public WhitegoodsCalculator(Clock clock) { this.clock = clock; }
    public int validate(LocalDate date, BigDecimal count) {
        if (date == null || date.isBefore(LocalDate.of(1, 1, 1)) || date.isAfter(LocalDate.now(clock))) {
            invalid("Record date must be a past or current date in Australia/Hobart.");
        }
        if (count == null || count.signum() < 0 || count.stripTrailingZeros().scale() > 0
                || count.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            invalid("Count must be a whole number from 0 to 2147483647.");
        }
        return count.intValueExact();
    }
    public BigDecimal estimatedWeight(long count) { return BigDecimal.valueOf(count).multiply(KG_PER_ITEM); }
    public YearMonth month(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}")) invalid("Month must use YYYY-MM.");
        try {
            var month = YearMonth.parse(value);
            if (month.getYear() < 1 || month.isAfter(YearMonth.now(clock))) invalid("Month must be past or current in Australia/Hobart.");
            return month;
        } catch (DateTimeParseException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid calendar month."); }
    }
    private static void invalid(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
