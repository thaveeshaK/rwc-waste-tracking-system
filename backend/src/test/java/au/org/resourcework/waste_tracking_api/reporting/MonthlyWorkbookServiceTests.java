package au.org.resourcework.waste_tracking_api.reporting;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MonthlyWorkbookServiceTests {
    @Test void rowSafetyLimitRejectsOversizedExportsRatherThanReturningATruncatedWorkbook() {
        var repository=mock(ReportingRepository.class);
        var reports=mock(ReportService.class);
        when(reports.validate(any())).thenAnswer(call->call.getArgument(0));
        when(repository.monthlyWorkbookRows(any())).thenReturn(Collections.nCopies(100001,null));
        var service=new MonthlyWorkbookService(repository,reports);
        assertThatThrownBy(()->service.export("2001-02")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("100,000");
        verify(repository,never()).monthlyWorkbookGroups(any());
    }
}
