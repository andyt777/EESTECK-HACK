package ro.filegrid.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.classification.ClassificationRequest;
import ro.filegrid.classification.ClassificationResult;
import ro.filegrid.classification.ClassificationService;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileEvent;
import ro.filegrid.file.FileEventRepository;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileSource;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.RoutingDecision;
import ro.filegrid.policy.PolicyFileWatcher;
import ro.filegrid.policy.PolicyService;
import ro.filegrid.storage.StorageType;
import ro.filegrid.testsupport.TestImages;

/**
 * Integration test for Stage 3: policy loading, hot-reload from file and admin
 * API, validation refusal, deterministic routing before transfer, and the
 * decision API endpoint.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
class PolicyAndRoutingIT {

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    private static final Path POLICY_FILE = createTempPolicyFile();
    private static final String INITIAL_YAML = readInitialPolicyYaml();
    private static final byte[] MP4_HEAD = new byte[] {
            0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
            0x00, 0x00, 0x00, 0x00, 'm', 'p', '4', '2', 'i', 's', 'o', 'm'
    };

    private static Path createTempPolicyFile() {
        try {
            Path dir = Files.createTempDirectory("filegrid-policy-it-");
            Path target = dir.resolve("policies.yaml");
            Files.copy(Path.of("../config/policies.yaml"), target);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare temporary policies.yaml", e);
        }
    }

    private static String readInitialPolicyYaml() {
        try {
            return Files.readString(POLICY_FILE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void cleanupTempPolicyDir() throws IOException {
        Files.deleteIfExists(POLICY_FILE);
        Files.deleteIfExists(POLICY_FILE.getParent());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", META_DB::getJdbcUrl);
        registry.add("spring.datasource.username", META_DB::getUsername);
        registry.add("spring.datasource.password", META_DB::getPassword);
        registry.add("filegrid.store-db.url", STORE_DB::getJdbcUrl);
        registry.add("filegrid.store-db.username", STORE_DB::getUsername);
        registry.add("filegrid.store-db.password", STORE_DB::getPassword);
        registry.add("filegrid.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("filegrid.storage.s3.access-key", () -> "unused");
        registry.add("filegrid.storage.s3.secret-key", () -> "unused");
        registry.add("filegrid.storage.ftp.host", () -> "127.0.0.1");
        registry.add("filegrid.storage.ftp.port", () -> 1);
        registry.add("filegrid.storage.ftp.connect-timeout", () -> "2s");
        registry.add("filegrid.storage.health-interval", () -> "1h");
        registry.add("filegrid.policy.file", () -> POLICY_FILE.toString());
        registry.add("filegrid.policy.check-interval", () -> "60s");
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    PolicyFileWatcher fileWatcher;
    @Autowired
    PolicyService policyService;
    @Autowired
    ClassificationService classification;
    @Autowired
    RoutingService routing;
    @Autowired
    FileRecordRepository files;
    @Autowired
    FileEventRepository events;
    @Autowired
    JdbcTemplate metaDb;

    @Test
    @Order(1)
    void loadsPolicyFileAtStartupAsVersionOne() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/policies", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("version").asLong()).isEqualTo(1L);
        assertThat(body.get("source").asText()).isEqualTo("FILE");
        assertThat(body.get("policy").at("/categories/METER_READING/constraints/maxSizeBytes/DATABASE").asLong())
                .isEqualTo(1_048_576L);
        assertThat(body.get("file").get("lastErrors")).isEmpty();
    }

    @Test
    @Order(2)
    void reloadsModifiedPolicyFileWithoutRestartAndRecordsDiff() throws IOException {
        String updatedYaml = INITIAL_YAML.replace("DATABASE: 1048576", "DATABASE: 2097152");
        Files.writeString(POLICY_FILE, updatedYaml);
        fileWatcher.checkFile();

        ResponseEntity<JsonNode> active = rest.getForEntity("/policies", JsonNode.class);
        assertThat(active.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(active.getBody().get("version").asLong()).isEqualTo(2L);
        assertThat(active.getBody().get("source").asText()).isEqualTo("FILE");
        assertThat(active.getBody().get("policy")
                .at("/categories/METER_READING/constraints/maxSizeBytes/DATABASE").asLong())
                .isEqualTo(2_097_152L);
        assertThat(active.getBody().get("file").get("lastErrors")).isEmpty();

        ResponseEntity<JsonNode> history = rest.getForEntity("/policies/history", JsonNode.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = history.getBody().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("version").asLong()).isEqualTo(2L);
        assertThat(items.get(0).get("diff").get(0).get("path").asText())
                .isEqualTo("categories.METER_READING.constraints.maxSizeBytes.DATABASE");
    }

    @Test
    @Order(3)
    void refusesInvalidPolicyFileAndKeepsPreviousVersionActive() throws IOException {
        String invalidYaml = INITIAL_YAML.replace("schemaVersion: 1", "schemaVersion: 99");
        Files.writeString(POLICY_FILE, invalidYaml);
        fileWatcher.checkFile();

        ResponseEntity<JsonNode> active = rest.getForEntity("/policies", JsonNode.class);
        assertThat(active.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(active.getBody().get("version").asLong()).isEqualTo(2L);
        assertThat(active.getBody().get("policy")
                .at("/categories/METER_READING/constraints/maxSizeBytes/DATABASE").asLong())
                .isEqualTo(2_097_152L);
        assertThat(active.getBody().get("file").get("lastErrors")).isNotEmpty();
    }

    @Test
    @Order(4)
    void adminPutValidatesAndKeepsPrecedenceOverUnchangedFile() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/yaml"));

        String invalidYaml = INITIAL_YAML.replace("maxAttempts: 5", "maxAttempts: 0");
        ResponseEntity<JsonNode> badResponse = rest.exchange(
                "/admin/policies", HttpMethod.PUT, new HttpEntity<>(invalidYaml, headers), JsonNode.class);
        assertThat(badResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badResponse.getBody().get("title").asText()).isEqualTo("Politică refuzată");
        assertThat(badResponse.getBody().get("errors")).isNotEmpty();
        assertThat(policyService.active().version()).isEqualTo(2L);

        String adminYaml = INITIAL_YAML.replace("DATABASE: 1048576", "DATABASE: 3145728");
        ResponseEntity<JsonNode> okResponse = rest.exchange(
                "/admin/policies", HttpMethod.PUT, new HttpEntity<>(adminYaml, headers), JsonNode.class);
        assertThat(okResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(okResponse.getBody().get("version").asLong()).isEqualTo(3L);
        assertThat(okResponse.getBody().get("source").asText()).isEqualTo("ADMIN");
        assertThat(okResponse.getBody().get("author").asText()).isEqualTo("demo-user");
        assertThat(okResponse.getBody().get("changed").asBoolean()).isTrue();
        assertThat(okResponse.getBody().get("policy")
                .at("/categories/METER_READING/constraints/maxSizeBytes/DATABASE").asLong())
                .isEqualTo(3_145_728L);

        // Restore the last file version (v2): PolicyFileWatcher clears its error and leaves v3 (ADMIN) active.
        Files.writeString(POLICY_FILE, INITIAL_YAML.replace("DATABASE: 1048576", "DATABASE: 2097152"));
        fileWatcher.checkFile();
        policyService.refresh();

        ResponseEntity<JsonNode> afterFileCheck = rest.getForEntity("/policies", JsonNode.class);
        assertThat(afterFileCheck.getBody().get("version").asLong()).isEqualTo(3L);
        assertThat(afterFileCheck.getBody().get("source").asText()).isEqualTo("ADMIN");
        assertThat(afterFileCheck.getBody().get("file").get("lastErrors")).isEmpty();

        // Resubmitting identical YAML changes nothing.
        ResponseEntity<JsonNode> unchanged = rest.exchange(
                "/admin/policies", HttpMethod.PUT, new HttpEntity<>(adminYaml, headers), JsonNode.class);
        assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unchanged.getBody().get("changed").asBoolean()).isFalse();
        assertThat(unchanged.getBody().get("version").asLong()).isEqualTo(3L);
    }

    @Test
    @Order(5)
    void routesBeforeTransferAndExposesDecisionEndpoint() {
        // 1. All storages UP -> DRONE_VIDEO routed to primary S3
        upsertHealth(StorageType.DATABASE, "UP", 3L);
        upsertHealth(StorageType.FTP, "UP", 11L);
        upsertHealth(StorageType.S3, "UP", 14L);

        UUID primaryFileId = createFileRecord("inspectie-linii.mp4", 500_000_000L);
        ClassificationResult cls = classification.classify(new ClassificationRequest(
                null, "inspectie-linii.mp4", "video/mp4", MP4_HEAD, Map.of()));
        RoutingDecision primaryDecision = routing.route(primaryFileId, cls);

        assertThat(primaryDecision.getSelectedStorage()).isEqualTo(StorageType.S3);
        assertThat(primaryDecision.isFallbackUsed()).isFalse();
        assertThat(primaryDecision.getPolicyVersion()).isEqualTo("v3");

        FileRecord savedPrimary = files.findById(primaryFileId).orElseThrow();
        assertThat(savedPrimary.getStatus()).isEqualTo(FileStatus.DESTINATION_SELECTED);
        assertThat(savedPrimary.getStorageType()).isEqualTo(StorageType.S3);
        assertThat(savedPrimary.getStorageKey()).startsWith("videos/");
        assertThat(events.findByFileIdOrderByAtAsc(primaryFileId))
                .extracting(FileEvent::getType)
                .containsExactly(FileEventType.ROUTED);

        ResponseEntity<JsonNode> primaryApi = rest.getForEntity("/files/" + primaryFileId + "/decision", JsonNode.class);
        assertThat(primaryApi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(primaryApi.getBody().get("selectedStorage").asText()).isEqualTo("S3");
        assertThat(primaryApi.getBody().get("fileStatus").asText()).isEqualTo("DESTINATION_SELECTED");
        assertThat(primaryApi.getBody().get("fallbackUsed").asBoolean()).isFalse();
        assertThat(primaryApi.getBody().get("candidates")).hasSize(3);
        assertThat(primaryApi.getBody().get("explanationRo").asText()).contains("S3");

        // 2. S3 DOWN -> DRONE_VIDEO falls back to FTP
        upsertHealth(StorageType.S3, "DOWN", 0L);
        UUID fallbackFileId = createFileRecord("inspectie-fallback.mp4", 300_000_000L);
        RoutingDecision fallbackDecision = routing.route(fallbackFileId, cls);

        assertThat(fallbackDecision.getSelectedStorage()).isEqualTo(StorageType.FTP);
        assertThat(fallbackDecision.isFallbackUsed()).isTrue();

        ResponseEntity<JsonNode> fallbackApi = rest.getForEntity("/files/" + fallbackFileId + "/decision", JsonNode.class);
        assertThat(fallbackApi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fallbackApi.getBody().get("selectedStorage").asText()).isEqualTo("FTP");
        assertThat(fallbackApi.getBody().get("fallbackUsed").asBoolean()).isTrue();
        assertThat(fallbackApi.getBody().get("fallbackReason").asText()).contains("S3");

        // 3. All storages DOWN -> PENDING with reason saved
        upsertHealth(StorageType.FTP, "DOWN", 0L);
        upsertHealth(StorageType.DATABASE, "DOWN", 0L);
        UUID pendingFileId = createFileRecord("inspectie-pending.mp4", 300_000_000L);
        RoutingDecision pendingDecision = routing.route(pendingFileId, cls);

        assertThat(pendingDecision.getSelectedStorage()).isNull();
        assertThat(files.findById(pendingFileId).orElseThrow().getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(events.findByFileIdOrderByAtAsc(pendingFileId))
                .extracting(FileEvent::getType)
                .containsExactly(FileEventType.STATUS_CHANGED);

        ResponseEntity<JsonNode> pendingApi = rest.getForEntity("/files/" + pendingFileId + "/decision", JsonNode.class);
        assertThat(pendingApi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pendingApi.getBody().get("selectedStorage").isNull()).isTrue();
        assertThat(pendingApi.getBody().get("fileStatus").asText()).isEqualTo("PENDING");
        assertThat(pendingApi.getBody().get("explanationRo").asText()).contains("așteptare");

        // 4. Unknown file returns 404
        ResponseEntity<JsonNode> missing = rest.getForEntity("/files/" + UUID.randomUUID() + "/decision", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID createFileRecord(String name, long sizeBytes) {
        UUID id = UUID.randomUUID();
        FileRecord record = new FileRecord(id, FileCategory.DRONE_VIDEO, name, sizeBytes,
                DataOrigin.REAL, FileSource.UI, "demo-user", Instant.now());
        record.setTraceId("trace-" + id);
        files.saveAndFlush(record);
        return id;
    }

    private void upsertHealth(StorageType storage, String status, long latencyMs) {
        metaDb.update("""
                INSERT INTO storage_health (storage, status, last_check, latency_ms, detail)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (storage) DO UPDATE
                    SET status = EXCLUDED.status, last_check = EXCLUDED.last_check,
                        latency_ms = EXCLUDED.latency_ms, detail = EXCLUDED.detail
                """, storage.name(), status, Timestamp.from(Instant.now()), latencyMs, "test");
    }
}
