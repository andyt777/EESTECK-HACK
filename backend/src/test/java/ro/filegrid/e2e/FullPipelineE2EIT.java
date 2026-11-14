package ro.filegrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.IntegrityStatus;
import ro.filegrid.storage.StorageProperties;
import ro.filegrid.storage.s3.S3ClientConfig;
import ro.filegrid.storage.s3.S3StorageAdapter;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.SseTestReceiver;
import ro.filegrid.testsupport.TestImages;
import ro.filegrid.testsupport.TusTestClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Tier 3 Cross-Feature End-to-End Pipeline Integration Test (T3-INT-20).
 * Exercises the complete multi-stage pipeline without shortcuts:
 * Tus 1.0 Client (POST + PATCH) -> TusUploadController -> Direct Staging ->
 * Ingestion (Plaintext SHA-256, Classification, Routing) -> RabbitMQ Category Queuing ->
 * Worker Transfer & Progress -> Read-back Hash Verification ->
 * Staging Cleanup -> Real-time Live SSE Event Sequence.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "worker"})
@Testcontainers
class FullPipelineE2EIT {

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

    private static S3Client s3Client;
    private static S3StorageAdapter s3Adapter;
    private static final String BUCKET = "filegrid-videos";
    private static final int PART_SIZE = 16 * 1024 * 1024;

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
        registry.add("filegrid.storage.health-interval", () -> "1h");
        registry.add("filegrid.policy.file", () -> "src/test/resources/policy/fast-retry-policies.yaml");
        registry.add("filegrid.worker.categories", () -> "DRONE_VIDEO");
        registry.add("filegrid.worker.concurrency", () -> "2");
    }

    @BeforeAll
    static void initS3() {
        StorageProperties.S3 s3Props = new StorageProperties.S3(
                URI.create(S3.getS3URL()), "us-east-1", S3.getUserName(), S3.getPassword(),
                "filegrid-", "filegrid-benchmark", PART_SIZE);
        s3Client = S3ClientConfig.create(s3Props);
        s3Client.createBucket(b -> b.bucket(BUCKET));
        // The health probe of the S3 storage checks this bucket.
        s3Client.createBucket(b -> b.bucket("filegrid-benchmark"));
        s3Adapter = new S3StorageAdapter(s3Client, new StorageProperties(null, s3Props, null, null));
    }

    @LocalServerPort
    int port;

    @Autowired
    private FileRecordRepository fileRecords;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("T3-INT-20: Full pipeline cross-feature integration: upload -> staging -> sha256 -> transfer -> read-back -> SSE")
    void fullPipelineEndToEndFlow() throws Exception {
        String baseUrl = "http://localhost:" + port + "/api";
        long totalBytes = 256 * 1024; // 256KB test payload
        DeterministicContent content = new DeterministicContent(4242, totalBytes);
        String expectedHash = content.sha256();

        // 1. Create the upload; the server assigns the file id (the last segment of Location).
        TusTestClient tusClient = new TusTestClient(baseUrl);
        Map<String, String> metadata = Map.of(
                "filename", "full-pipeline-test.mp4",
                "category", "DRONE_VIDEO",
                "zone", "Banat",
                "station", "Statia_Timisoara1"
        );
        URI uploadUri = tusClient.createUpload(totalBytes, metadata);
        assertThat(uploadUri).isNotNull();
        UUID fileId = UUID.fromString(uploadUri.getPath().substring(uploadUri.getPath().lastIndexOf('/') + 1));

        // 2. Connect the SSE receiver BEFORE the last bytes are sent, to capture every event
        try (SseTestReceiver sseReceiver = SseTestReceiver.connect(baseUrl, fileId, null)) {

            // 3. Stream chunk to /api/uploads via Tus PATCH
            try (InputStream in = content.open()) {
                tusClient.uploadChunk(uploadUri, in, 0);
            }

            // 4. Await asynchronous completion via live SSE stream
            JsonNode finalEvent = sseReceiver.awaitStatus(fileId, "STORED_VERIFIED", Duration.ofSeconds(20));
            assertThat(finalEvent).isNotNull();

            // 5. Assert live SSE event sequence
            List<JsonNode> events = sseReceiver.getEventsForFile(fileId);
            List<String> statuses = events.stream().map(e -> e.get("status").asText()).toList();
            assertThat(statuses).containsSubsequence(
                    "RECEIVED", "ANALYZING", "CATEGORIZED", "DESTINATION_SELECTED", "TRANSFERRING", "STORED_VERIFIED"
            );

            // 6. Assert Agent 01 Romanian messages and schema for every status
            for (JsonNode event : events) {
                assertThat(event.get("message").asText()).matches(SseTestReceiver.ROMANIAN_AGENT01_PATTERN);
                assertThat(event.get("fileId").asText()).isEqualTo(fileId.toString());
                assertThat(event.get("traceId").asText()).isNotBlank();
            }

            // 7. Verify S3 storage object
            FileRecord record = fileRecords.findById(fileId).orElseThrow();
            assertThat(record.getStatus()).isEqualTo(FileStatus.STORED_VERIFIED);
            assertThat(record.getIntegrityStatus()).isEqualTo(IntegrityStatus.VERIFIED_INTACT);
            assertThat(record.getSha256Plain()).isEqualTo(expectedHash);

            try (InputStream s3Stream = s3Adapter.get(record.getStorageKey())) {
                assertThat(DeterministicContent.sha256Of(s3Stream)).isEqualTo(expectedHash);
            }

            // 8. Verify staging file was automatically cleaned up
            Path stagingFile = stagingDir.resolve(fileId.toString());
            assertThat(Files.exists(stagingFile))
                    .as("Staging file must be cleaned up after successful storage and verification")
                    .isFalse();
        }
    }
}
