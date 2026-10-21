package au.org.resourcework.waste_tracking_api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Runs before bean creation, Flyway migration, or initial-account provisioning. */
public class IsolatedTestDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Pattern URL = Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]+)/rwc_slice_test");

    static Settings settings() {
        return new Settings(System.getenv("RWC_TEST_DB_URL"), System.getenv("RWC_TEST_DB_USERNAME"),
                System.getenv("RWC_TEST_DB_PASSWORD"), System.getenv("RWC_TEST_DB_TOKEN"));
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var settings = settings();
        try (var connection = DriverManager.getConnection(settings.url(), settings.username(), settings.password())) {
            verify(connection, settings);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot verify the isolated test database. No tests may modify data.", exception);
        }
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("verified-isolated-test-database", settings.properties()));
    }

    static void verify(Connection connection, Settings settings) throws SQLException {
        if (!settings.url().equals(connection.getMetaData().getURL())) {
            throw new IllegalStateException("Test datasource does not point to the verified temporary database.");
        }
        var match = URL.matcher(settings.url());
        if (!match.matches()) throw new IllegalStateException("Invalid isolated database URL.");
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT current_database() AS database_name, current_user AS user_name,
                       host(inet_server_addr()) AS server_address, inet_server_port() AS server_port,
                       shobj_description(oid, 'pg_database') AS isolation_token
                FROM pg_database WHERE datname = current_database()
                """)) {
            if (!result.next() || !"rwc_slice_test".equals(result.getString("database_name"))
                    || !settings.username().equals(result.getString("user_name"))
                    || !"127.0.0.1".equals(result.getString("server_address"))
                    || Integer.parseInt(match.group(1)) != result.getInt("server_port")
                    || !settings.token().equals(result.getString("isolation_token"))) {
                throw new IllegalStateException("Test database identity verification failed. No data may be modified.");
            }
        }
    }

    record Settings(String url, String username, String password, String token) {
        Settings {
            if (url == null || !URL.matcher(url).matches() || !"rwc_test".equals(username)
                    || password == null || password.isBlank() || token == null) {
                throw new IllegalStateException("Use scripts/test-backend.ps1 to supply an isolated test database.");
            }
            UUID.fromString(token);
        }

        Map<String, Object> properties() {
            return Map.ofEntries(
                    Map.entry("spring.datasource.url", url),
                    Map.entry("spring.datasource.username", username),
                    Map.entry("spring.datasource.password", password),
                    Map.entry("spring.datasource.driver-class-name", "org.postgresql.Driver"),
                    Map.entry("spring.datasource.hikari.jdbc-url", url),
                    Map.entry("spring.datasource.hikari.username", username),
                    Map.entry("spring.datasource.hikari.password", password),
                    Map.entry("spring.datasource.hikari.schema", "public"),
                    Map.entry("spring.flyway.url", url),
                    Map.entry("spring.flyway.user", username),
                    Map.entry("spring.flyway.password", password),
                    Map.entry("spring.flyway.default-schema", "public"),
                    Map.entry("spring.flyway.schemas", "public"));
        }
    }
}
