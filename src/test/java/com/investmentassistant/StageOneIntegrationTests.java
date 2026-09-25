package com.investmentassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.investmentassistant.persistence.DatabaseStatusProbe;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StageOneIntegrationTests {

    private static final Path DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "investment-assistant-" + UUID.randomUUID() + ".db");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("app.database.path", DATABASE_PATH::toString);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private DatabaseStatusProbe databaseStatusProbe;

    @LocalServerPort
    private int port;

    @Test
    void flywayCreatesSchemaAndSeedsMetadata() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        String schemaVersion = jdbcTemplate.queryForObject(
                "SELECT \"value\" FROM app_metadata WHERE \"key\" = 'schema.version'", String.class);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(migrationCount).isEqualTo(1);
        assertThat(schemaVersion).isEqualTo("1");
        assertThat(Files.isRegularFile(DATABASE_PATH)).isTrue();
    }

    @Test
    void sqliteCanBeQueriedAndReportsUp() {
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(databaseStatusProbe.isAvailable()).isTrue();
    }

    @Test
    void statusEndpointReportsApplicationAndDatabaseUp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/status"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(
                "{\"status\":\"UP\",\"database\":\"UP\",\"application\":\"investment-assistant\"}");
    }
}
