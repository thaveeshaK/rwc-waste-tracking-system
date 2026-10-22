package au.org.resourcework.waste_tracking_api.electricaltesting;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
@Component
public class ElectricalTestingValidation {
    private final Clock clock;
    private final ElectricalTestingCategoryRepository categories;
    public ElectricalTestingValidation(Clock clock, ElectricalTestingCategoryRepository categories) { this.clock=clock; this.categories=categories; }
    public void date(LocalDate value) {
        if (value == null || value.isBefore(LocalDate.of(1,1,1)) || value.isAfter(LocalDate.now(clock))) invalid("Date must be past or current in Australia/Hobart.");
    }
    public LocalDate month(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}")) invalid("Month must use YYYY-MM.");
        try {
            var month=YearMonth.parse(value);
            if (month.getYear()<1 || month.isAfter(YearMonth.now(clock))) invalid("Month must be past or current in Australia/Hobart.");
            return month.atDay(1);
        } catch (DateTimeParseException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid calendar month."); }
    }
    public int count(BigDecimal value, boolean allowZero) {
        if (value == null || value.signum()<0 || (!allowZero && value.signum()==0) || value.stripTrailingZeros().scale()>0 || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE))>0)
            invalid(allowZero ? "Count must be a whole number from 0 to 2147483647." : "Tally count must be a whole number greater than 0, at most 2147483647.");
        return value.intValueExact();
    }
    public ElectricalTestingCategory category(Long id, ElectricalTestingCategory historical) {
        if (id == null) invalid("An electrical item category is required.");
        var category=categories.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown electrical item category."));
        if (!category.isActive() && (historical == null || !historical.getId().equals(id))) invalid("This electrical item category is inactive.");
        return category;
    }
    public static void version(Long requested, Long saved) {
        if (requested == null) invalid("The record version is required for a correction.");
        if (!requested.equals(saved)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This record has changed. Reload it before correcting it.");
    }
    public static void invalid(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
