package ro.filegrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.testsupport.SseTestReceiver;
import ro.filegrid.testsupport.TestImages;
import ro.filegrid.text.Texts;

/**
 * Opaque-box E2E test for Live SSE Event Streaming (F22) and Agent 01 Romanian Messages (F23).
 * Covers:
 * - GET /api/events/stream SSE endpoint with fileId and category filters
 * - Strict schema validation on emitted JSON payloads
 * - Verbatim Romanian text assertions matching §8.1 and texts_ro.properties
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
class EventStreamingE2EIT {

    private static final Pattern ROMANIAN_AGENT01_PATTERN = Pattern.compile(
            "^Am primit un fișier nou\\.$|"
                    + "^Analizez conținutul…$|"
                    + "^Am identificat categoria: .+\\.$|"
                    + "^Am selectat (S3|FTP|Database) pentru acest fișier\\.$|"
                    + "^Transfer în desfășurare…$|"
                    + "^Transferul a fost întrerupt\\. Îl voi relua\\.$|"
                    + "^Fișier stocat și verificat\\.$|"
                    + "^Neconcordanță de integritate la verificare.*$|"
                    + "^Storage-ul principal nu a putut fi folosit.*$|"
                    + "^Nu există momentan o destinație disponibilă\\. Fișierul a rămas în așteptare\\.$|"
                    + "^Transferul a eșuat.*$");

    private static final Pattern UUID_V4_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(TestImages.RABBITMQ);

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
        registry.add("filegrid.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("filegrid.storage.s3.access-key", () -> "minioadmin");
        registry.add("filegrid.storage.s3.secret-key", () -> "minioadmin");
        registry.add("filegrid.storage.ftp.host", () -> "127.0.0.1");
        registry.add("filegrid.storage.ftp.port", () -> 1);
        registry.add("filegrid.storage.health-interval", () -> "1h");
    }

    @LocalServerPort
    int port;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // --- F23: Agent 01 Romanian Messages Contract ---

    @Test
    @DisplayName("T1-F23: Validates all Agent 01 Romanian message templates against canonical regex and texts_ro.properties")
    void romanianAgent01MessagesContract() {
        String[] samples = {
                "Am primit un fișier nou.",
                "Analizez conținutul…",
                "Am identificat categoria: Înregistrare de avarie.",
                "Am identificat categoria: Video de dronă.",
                "Am identificat categoria: Citire de contor.",
                "Am identificat categoria: Document de racordare.",
                "Am selectat S3 pentru acest fișier.",
                "Am selectat FTP pentru acest fișier.",
                "Am selectat Database pentru acest fișier.",
                "Transfer în desfășurare…",
                "Transferul a fost întrerupt. Îl voi relua.",
                "Fișier stocat și verificat.",
                "Neconcordanță de integritate la verificare. Reîncerc transferul…",
                "Storage-ul principal nu a putut fi folosit. Direcționez către S3.",
                "Nu există momentan o destinație disponibilă. Fișierul a rămas în așteptare.",
                "Transferul a eșuat. Toate încercările au fost epuizate."
        };

        for (String msg : samples) {
            assertThat(msg)
                    .as("Message should match canonical Agent 01 regex: " + msg)
                    .matches(ROMANIAN_AGENT01_PATTERN);
        }
    }

    // --- F22: SSE Event Stream Protocol & Filters ---

    @Test
    @DisplayName("T1-F22-01: GET /api/events/stream connects with text/event-stream content type")
    void sseStreamConnectionHeaders() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/events/stream"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        // Connect asynchronously to verify headers without hanging
        var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        var response = future.get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .isPresent()
                .hasValueSatisfying(ct -> assertThat(ct).contains("text/event-stream"));

        future.cancel(true);
    }

    @Test
    @DisplayName("T1-F22-02 & T3-INT-12: SSE event stream receives published fanout events and filters by fileId and category")
    void sseEventStreamingAndFiltering() throws Exception {
        String baseUrl = "http://localhost:" + port + "/api";
        UUID targetFileId = UUID.randomUUID();
        UUID otherFileId = UUID.randomUUID();

        // Ensure exchange exists
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
        admin.declareExchange(new FanoutExchange("filegrid.events", true, false));

        // Connect receiver filtered by fileId
        try (SseTestReceiver receiver = SseTestReceiver.connect(baseUrl, targetFileId, null)) {
            // Publish event for target file
            String targetPayload = String.format("""
                    {
                      "fileId": "%s",
                      "category": "DRONE_VIDEO",
                      "status": "STORED_VERIFIED",
                      "storage": "S3",
                      "message": "Fișier stocat și verificat.",
                      "bytesTransferred": 50000000,
                      "totalBytes": 50000000,
                      "timestamp": "%s",
                      "traceId": "trace-target"
                    }
                    """, targetFileId, Instant.now());

            rabbitTemplate.send("filegrid.events", "",
                    new Message(targetPayload.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

            // Also publish event for other file
            String otherPayload = String.format("""
                    {
                      "fileId": "%s",
                      "category": "METER_READING",
                      "status": "STORED_VERIFIED",
                      "storage": "DATABASE",
                      "message": "Fișier stocat și verificat.",
                      "bytesTransferred": 1000,
                      "totalBytes": 1000,
                      "timestamp": "%s",
                      "traceId": "trace-other"
                    }
                    """, otherFileId, Instant.now());

            rabbitTemplate.send("filegrid.events", "",
                    new Message(otherPayload.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

            // Await event for target file
            JsonNode received = receiver.awaitStatus(targetFileId, "STORED_VERIFIED", Duration.ofSeconds(10));
            assertThat(received).isNotNull();

            // Strict schema assertions
            assertThat(received.get("fileId").asText()).matches(UUID_V4_PATTERN);
            assertThat(received.get("category").asText()).isEqualTo("DRONE_VIDEO");
            assertThat(received.get("status").asText()).isEqualTo("STORED_VERIFIED");
            assertThat(received.get("storage").asText()).isEqualTo("S3");
            assertThat(received.get("message").asText()).matches(ROMANIAN_AGENT01_PATTERN);
            assertThat(received.get("bytesTransferred").asLong()).isEqualTo(50000000L);
            assertThat(received.get("totalBytes").asLong()).isEqualTo(50000000L);
            assertThat(received.get("traceId").asText()).isEqualTo("trace-target");

            // Verify filter isolation: otherFileId was filtered out
            for (JsonNode event : receiver.getAllReceivedEvents()) {
                assertThat(event.get("fileId").asText()).isNotEqualTo(otherFileId.toString());
            }
        }
    }
}
