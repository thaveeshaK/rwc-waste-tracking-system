package au.org.resourcework.waste_tracking_api.electricaltesting;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ElectricalTestingValidationTests {
    private final ElectricalTestingValidation validation=new ElectricalTestingValidation(Clock.fixed(Instant.parse("2026-10-31T14:00:00Z"),ZoneId.of("Australia/Hobart")),null);
    @Test void zeroOnlyValidForReferencesAndIntegersAreRequired(){assertThat(validation.count(BigDecimal.ZERO,true)).isZero();assertThatThrownBy(()->validation.count(BigDecimal.ZERO,false)).hasMessageContaining("greater than 0");assertThatThrownBy(()->validation.count(new BigDecimal("1.5"),true)).hasMessageContaining("whole number");}
    @Test void hobartDatesAndReportingMonths(){validation.date(LocalDate.of(2026,11,1));assertThat(validation.month("2026-11")).isEqualTo(LocalDate.of(2026,11,1));assertThatThrownBy(()->validation.month("2026-12")).hasMessageContaining("Australia/Hobart");assertThatThrownBy(()->validation.date(LocalDate.of(2026,11,2))).hasMessageContaining("Australia/Hobart");}
    @Test void categoriesRequiredOnlyForThreeProcessingTypes(){assertThat(TallyType.TESTED_AND_PRICED.requiresCategory()).isTrue();assertThat(TallyType.UNTESTED_BROKEN_FAILED.requiresCategory()).isTrue();assertThat(TallyType.STOCK_CULL.requiresCategory()).isTrue();assertThat(TallyType.DIVERTED_TO_OLS.requiresCategory()).isFalse();assertThat(TallyType.DIVERTED_TO_COLL.requiresCategory()).isFalse();}
}
