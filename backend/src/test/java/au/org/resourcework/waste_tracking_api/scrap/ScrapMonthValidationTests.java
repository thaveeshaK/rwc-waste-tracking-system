package au.org.resourcework.waste_tracking_api.scrap;

import au.org.resourcework.waste_tracking_api.auth.AppUserRepository;
import au.org.resourcework.waste_tracking_api.scrap.dto.ScrapRequest;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScrapMonthValidationTests {
    private ScrapService service(Instant instant) {
        var records=mock(ScrapRepository.class);
        var totals=mock(ScrapRepository.Totals.class, invocation->BigDecimal.ZERO);
        when(records.totals(any(),isNull())).thenReturn(totals);
        return new ScrapService(records,mock(AppUserRepository.class),Clock.fixed(instant,ZoneId.of("Australia/Hobart")));
    }
    private ScrapRequest entry(String month) {
        return new ScrapRequest(UUID.randomUUID(),month,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,null);
    }
    @Test void currentMonthChangesAtHobartMidnightWhileUtcIsStillInPreviousMonth() {
        var before=Instant.parse("2026-03-31T12:59:59Z");
        var after=before.plusSeconds(1);
        assertThat(YearMonth.from(after.atZone(ZoneOffset.UTC))).isEqualTo(YearMonth.of(2026,3));
        assertThat(YearMonth.from(after.atZone(ZoneId.of("Australia/Hobart")))).isEqualTo(YearMonth.of(2026,4));
        assertThat(service(before).preview(entry("2026-02")).entry().reportingMonth()).isEqualTo("2026-02");
        assertThat(service(before).preview(entry("2026-03")).entry().reportingMonth()).isEqualTo("2026-03");
        assertThatThrownBy(()->service(before).preview(entry("2026-04"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("future (Australia/Hobart)");
        assertThat(service(after).preview(entry("2026-04")).entry().reportingMonth()).isEqualTo("2026-04");
        assertThatThrownBy(()->service(after).preview(entry("2026-05"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("future (Australia/Hobart)");
        assertThatThrownBy(()->service(after).preview(entry("2027-01"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("future (Australia/Hobart)");
    }
}
