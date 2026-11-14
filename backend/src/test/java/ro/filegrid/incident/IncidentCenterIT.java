package ro.filegrid.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.testsupport.TestImages;

/**
 * Security &amp; Incident Center end to end: manual report with simulated
 * WhatsApp notifications, confirmation, escalation, resolution with the final
 * update, deduplication, and the automatic incident for an unreachable storage
 * (FTP points to a closed port here).
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
class IncidentCenterIT {

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(TestImages.RABBITMQ);

    @Container
    static final MinIOContainer S3 = new MinIOContainer(TestImages.S3);

    @TempDir
    static Path stagingDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", META_DB::getJdbcUrl);
        registry.add("spring.datasource.username", META_DB::getUsername);
        registry.add("spring.datasource.password", META_DB::getPassword);
        registry.add("filegrid.store-db.url", STORE_DB::getJdbcUrl);
        registry.add("filegrid.store-db.username", STORE_DB::getUsername);
        registry.add("filegrid.store-db.password", STORE_DB::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("filegrid.staging.directory", () -> stagingDir.toAbsolutePath().toString());
        registry.add("filegrid.storage.s3.endpoint", S3::getS3URL);
        registry.add("filegrid.storage.s3.access-key", S3::getUserName);
        registry.add("filegrid.storage.s3.secret-key", S3::getPassword);
        registry.add("filegrid.storage.ftp.host", () -> "127.0.0.1");
        registry.add("filegrid.storage.ftp.port", () -> 1);
        registry.add("filegrid.storage.ftp.connect-timeout", () -> "2s");
        registry.add("filegrid.storage.health-interval", () -> "2s");
        registry.add("filegrid.notify.escalation-override", () -> "4s");
        registry.add("filegrid.incident.escalation-check", () -> "1000");
        registry.add("filegrid.incident.detect-interval", () -> "1000");
    }

    @Autowired
    TestRestTemplate http;

    private static final Map<String, Object> FAULT = Map.of("type", "GRID_FAULT_MAJOR", "severity", "HIGH",
            "certainty", "CONFIRMED", "zone", "Muntenia", "station", "Stația Nord", "asset", "Trafo T2",
            "description", "Declanșare protecție pe linia de 110 kV", "dataOrigin", "SIMULATED");

    @Test
    @DisplayName("manual report → simulated WhatsApp to level 1 → OK → resolved with a final update")
    void lifecycle() {
        JsonNode preview = http.postForObject("/incidents/preview", FAULT, JsonNode.class);
        assertThat(preview).hasSize(3);
        assertThat(preview.get(0).get("body").asText()).contains("Stația Nord");

        JsonNode detail = http.postForObject("/incidents", FAULT, JsonNode.class);
        String id = detail.at("/incident/id").asText();
        assertThat(detail.at("/incident/status").asText()).isEqualTo("NOTIFIED");
        assertThat(detail.at("/incident/code").asText()).startsWith("INC-");
        JsonNode notifications = detail.get("notifications");
        assertThat(notifications).hasSize(3);
        notifications.forEach(n -> {
            assertThat(n.get("status").asText()).isEqualTo("SIMULATED");
            assertThat(n.get("body").asText()).contains(detail.at("/incident/code").asText());
        });

        JsonNode confirmed = http.postForObject("/incidents/notifications/" + notifications.get(0).get("id")
                .asText() + "/confirm", Map.of(), JsonNode.class);
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(http.getForObject("/incidents/" + id, JsonNode.class).at("/incident/status").asText())
                .isEqualTo("ACKNOWLEDGED");

        ResponseEntity<JsonNode> noNote = http.postForEntity("/incidents/" + id + "/status",
                Map.of("status", "RESOLVED"), JsonNode.class);
        assertThat(noNote.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode resolved = http.postForObject("/incidents/" + id + "/status",
                Map.of("status", "RESOLVED", "note", "Linia a fost repusă sub tensiune"), JsonNode.class);
        assertThat(resolved.at("/incident/status").asText()).isEqualTo("RESOLVED");
        long updates = 0;
        for (JsonNode n : resolved.get("notifications")) {
            if ("UPDATE".equals(n.get("kind").asText())) {
                updates++;
                assertThat(n.get("body").asText()).contains("Linia a fost repusă sub tensiune");
            }
        }
        assertThat(updates).isEqualTo(3);
    }

    @Test
    @DisplayName("nobody confirms → the next level is notified")
    void escalation() {
        JsonNode detail = http.postForObject("/incidents", Map.of("type", "FIELD_EMERGENCY", "severity",
                "CRITICAL", "zone", "Banat", "description", "Echipă blocată", "dataOrigin", "SIMULATED"),
                JsonNode.class);
        String id = detail.at("/incident/id").asText();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode now = http.getForObject("/incidents/" + id, JsonNode.class);
            boolean level2 = false;
            for (JsonNode n : now.get("notifications")) {
                level2 |= n.get("level").asInt() == 2;
            }
            assertThat(level2).isTrue();
        });
    }

    @Test
    @DisplayName("a fire is never inferred by the system; same fingerprint → same incident")
    void rulesAndDeduplication() {
        ResponseEntity<JsonNode> fire = http.postForEntity("/incidents", Map.of("type", "FIRE_REPORTED",
                "severity", "CRITICAL", "source", "SYSTEM"), JsonNode.class);
        assertThat(fire.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> anomaly = Map.of("type", "GRID_ANOMALY", "severity", "MEDIUM", "certainty",
                "SUSPECTED", "source", "SYSTEM", "dataOrigin", "SIMULATED", "fingerprint", "grid-anomaly:test");
        String a = http.postForObject("/incidents", anomaly, JsonNode.class).at("/incident/id").asText();
        String b = http.postForObject("/incidents", anomaly, JsonNode.class).at("/incident/id").asText();
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("unreachable FTP → STORAGE_UNAVAILABLE incident opened automatically")
    void storageDown() {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            JsonNode page = http.getForObject("/incidents?type=STORAGE_UNAVAILABLE&open=true", JsonNode.class);
            assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(1);
            boolean ftp = false;
            for (JsonNode item : page.get("items")) {
                JsonNode detail = http.getForObject("/incidents/" + item.get("id").asText(), JsonNode.class);
                ftp |= detail.at("/notifications/0/body").asText().contains("*Serviciu:* FTP");
            }
            assertThat(ftp).isTrue();
        });
        JsonNode health = http.getForObject("/storage/health", JsonNode.class);
        assertThat(health.isArray()).isTrue();
    }
}
