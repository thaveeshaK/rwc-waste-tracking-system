package au.org.resourcework.waste_tracking_api;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IsolatedTestDatabaseTests {
    private final IsolatedTestDatabase.Settings settings = new IsolatedTestDatabase.Settings(
            "jdbc:postgresql://127.0.0.1:54321/rwc_slice_test", "rwc_test",
            UUID.randomUUID().toString(), UUID.randomUUID().toString());

    @Test
    void explicitConnectionSettingsOverrideConflictingSpringProperties() {
        var environment = new MockEnvironment();
        for (var key : settings.properties().keySet()) environment.setProperty(key, "inherited-setting");
        environment.getPropertySources().addFirst(new MapPropertySource("isolated", settings.properties()));
        settings.properties().forEach((key, value) -> assertThat(environment.getProperty(key)).isEqualTo(value));
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(settings.url());
        assertThat(environment.getProperty("spring.datasource.hikari.jdbc-url")).isEqualTo(settings.url());
        assertThat(environment.getProperty("spring.flyway.url")).isEqualTo(settings.url());
    }

    @Test
    void rejectsNonIsolatedConnectionSettings() {
        for (var url : new String[] {"jdbc:postgresql://localhost:5432/rwc_slice_test",
                "jdbc:postgresql://127.0.0.1:54321/operational", settings.url() + "?currentSchema=other"}) {
            assertThatThrownBy(() -> new IsolatedTestDatabase.Settings(url, settings.username(), settings.password(), settings.token()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsRedirectedConnectionBeforeRunningAnySql() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://127.0.0.1:5432/operational");
        assertThatThrownBy(() -> IsolatedTestDatabase.verify(connection, settings)).isInstanceOf(IllegalStateException.class);
        verify(connection, never()).createStatement();
    }

    @Test
    void rejectsDatabaseWithTheRightNameButWrongIsolationToken() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(settings.url());
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString("database_name")).thenReturn("rwc_slice_test");
        when(result.getString("user_name")).thenReturn("rwc_test");
        when(result.getString("server_address")).thenReturn("127.0.0.1");
        when(result.getInt("server_port")).thenReturn(54321);
        when(result.getString("isolation_token")).thenReturn("not-this-run");
        assertThatThrownBy(() -> IsolatedTestDatabase.verify(connection, settings)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void teardownDoesNotTouchDatabaseWhenSetupWasNotVerified() throws Exception {
        var tests = new CratePalletIntegrationTests();
        tests.jdbc = mock(JdbcTemplate.class);
        tests.restoreReferenceData();
        verifyNoInteractions(tests.jdbc);
    }
}
