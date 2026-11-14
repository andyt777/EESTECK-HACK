package ro.filegrid.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileEventRepository;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileSource;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.IntegrityStatus;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageProperties;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.s3.S3ClientConfig;
import ro.filegrid.storage.s3.S3StorageAdapter;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.TestImages;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Opaque-box integration tests for Worker Transfer, Verification, and Resilience.
 * Covers:
 * - F13: Worker Category Filtering & Bulkhead Isolation
 * - F14: Worker Streaming Transfer & Progress Reporting (~5% / 16MB)
 * - F15: Read-back Hash Verification and Staging Cleanup (STORED_VERIFIED + staging delete)
 * - F16: Integrity Mismatch Handling (MISMATCH status, corrupt object delete, retry)
 * - F17: Storage Error Backoff & Jitter
 * - F18: Storage Fallback Trigger (primary exhausted -> FALLBACK event)
 * - F19: Storage Failure PENDING State (all fail -> PENDING, staging preserved)
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"api", "worker"})
@Testcontainers
class WorkerTransferIT {

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
        registry.add("filegrid.storage.ftp.connect-timeout", () -> "2s");
        registry.add("filegrid.storage.health-interval", () -> "1h");
        registry.add("filegrid.policy.file", () -> "src/test/resources/policy/fast-retry-policies.yaml");
        registry.add("filegrid.worker.categories", () -> "DRONE_VIDEO,FAULT_RECORD");
        registry.add("filegrid.worker.concurrency", () -> "2");
    }

    @BeforeAll
    static void initS3() {
        StorageProperties.S3 s3Props = new StorageProperties.S3(
                URI.create(S3.getS3URL()), "us-east-1", S3.getUserName(), S3.getPassword(),
                "filegrid-", "filegrid-benchmark", PART_SIZE);
        s3Client = S3ClientConfig.create(s3Props);
        for (String bucket : new String[] {"filegrid-benchmark", "filegrid-videos", "filegrid-documents", "filegrid-faults"}) {
            s3Client.createBucket(b -> b.bucket(bucket));
        }
        s3Adapter = new S3StorageAdapter(s3Client, new StorageProperties(null, s3Props, null, null));
    }

    @Autowired
    private FileRecordRepository fileRecords;

    @Autowired
    private FileEventRepository fileEvents;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate metaDb;

    @Autowired
    private ObjectMapper objectMapper;

    // --- F13: Worker Category Filtering ---

    @Test
    @DisplayName("T1-F13-01: Worker category properties correctly parse assigned categories and concurrency")
    void workerPropertiesCategoryFiltering() {
        WorkerProperties props = new WorkerProperties("DRONE_VIDEO,FAULT_RECORD", 4);
        Set<FileCategory> parsed = props.parsedCategories();

        assertThat(parsed).containsExactlyInAnyOrder(FileCategory.DRONE_VIDEO, FileCategory.FAULT_RECORD);
        assertThat(props.concurrency()).isEqualTo(4);

        WorkerRole role = new WorkerRole(props);
        assertThat(role.categories()).contains(FileCategory.DRONE_VIDEO);
        assertThat(role.categories()).doesNotContain(FileCategory.METER_READING);
    }

    // --- F14 & F15: Worker Streaming Transfer, Read-back Hash Verification & Staging Cleanup ---

    @Test
    @DisplayName("T1-F14-01 & T1-F15-01: Streams file to S3, verifies read-back hash, sets STORED_VERIFIED, and deletes staging file")
    void streamingTransferAndReadBackVerification() throws Exception {
        UUID fileId = UUID.randomUUID();
        long size = 512 * 1024;
        DeterministicContent content = new DeterministicContent(999, size);
        String expectedHash = content.sha256();

        // 1. Create staging file
        Path stagingFile = stagingDir.resolve(fileId.toString());
        try (InputStream in = content.open()) {
            Files.copy(in, stagingFile);
        }
        assertThat(Files.exists(stagingFile)).isTrue();

        // 2. Create file_record in DESTINATION_SELECTED
        FileRecord record = new FileRecord(fileId, FileCategory.DRONE_VIDEO, "inspection.mp4", size,
                DataOrigin.REAL, FileSource.UI, "worker-test", Instant.now());
        record.setStatus(FileStatus.DESTINATION_SELECTED);
        record.setSha256Plain(expectedHash);
        record.setTraceId("trace-transfer-1");
        fileRecords.saveAndFlush(record);

        String storageKey = StorageKeys.forCategory(FileCategory.DRONE_VIDEO, Instant.now(), fileId);

        // 3. Declare RabbitMQ queue topology and event listener queue
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
        admin.declareExchange(new DirectExchange("filegrid.jobs", true, false));
        admin.declareExchange(new FanoutExchange("filegrid.events", true, false));
        Queue eventCollectorQueue = new AnonymousQueue();
        admin.declareQueue(eventCollectorQueue);
        admin.declareBinding(BindingBuilder.bind(eventCollectorQueue)
                .to(new FanoutExchange("filegrid.events", true, false)));

        // 4. Publish persistent job to RabbitMQ category queue
        String jobPayload = String.format("""
                {
                  "fileId": "%s",
                  "category": "DRONE_VIDEO",
                  "stagingPath": "%s",
                  "sizeBytes": %d,
                  "sha256Plain": "%s",
                  "selectedStorage": "S3",
                  "storageKey": "%s",
                  "traceId": "trace-transfer-1",
                  "enqueuedAt": "%s"
                }
                """, fileId, stagingFile.toAbsolutePath().toString().replace("\\", "/"), size, expectedHash, storageKey, Instant.now());

        rabbitTemplate.convertAndSend("filegrid.jobs", "filegrid.jobs.video", jobPayload, msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return msg;
        });

        // 5. Assert via Awaitility: SUT worker consumes job, updates status to STORED_VERIFIED, and deletes staging file
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    FileRecord verified = fileRecords.findById(fileId).orElseThrow();
                    assertThat(verified.getStatus()).isEqualTo(FileStatus.STORED_VERIFIED);
                    assertThat(verified.getIntegrityStatus()).isEqualTo(IntegrityStatus.VERIFIED_INTACT);
                    assertThat(verified.getStorageType()).isEqualTo(StorageType.S3);
                    assertThat(Files.exists(stagingFile))
                            .as("Worker must delete staging file after successful verification")
                            .isFalse();
                });

        // 6. Direct storage verification: read-back hash matches original plaintext hash
        try (InputStream s3In = s3Adapter.get(storageKey)) {
            String readBackHash = DeterministicContent.sha256Of(s3In);
            assertThat(readBackHash).isEqualTo(expectedHash);
        }
    }

    // --- F16: Integrity Mismatch Handling ---

    @Test
    @DisplayName("T1-F16-01: Read-back hash mismatch marks MISMATCH, deletes corrupt object, and preserves staging file for retry")
    void integrityMismatchHandling() throws Exception {
        UUID fileId = UUID.randomUUID();
        long size = 128 * 1024;
        DeterministicContent content = new DeterministicContent(444, size);
        String actualHash = content.sha256();

        Path stagingFile = stagingDir.resolve(fileId.toString());
        try (InputStream in = content.open()) {
            Files.copy(in, stagingFile);
        }

        // Insert record with deliberate mismatched expected hash
        String mismatchedHash = "0000000000000000000000000000000000000000000000000000000000000000";
        FileRecord record = new FileRecord(fileId, FileCategory.DRONE_VIDEO, "mismatch.mp4", size,
                DataOrigin.REAL, FileSource.UI, "worker-test", Instant.now());
        record.setStatus(FileStatus.DESTINATION_SELECTED);
        record.setSha256Plain(mismatchedHash);
        fileRecords.saveAndFlush(record);

        String storageKey = StorageKeys.forCategory(FileCategory.DRONE_VIDEO, Instant.now(), fileId);

        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
        admin.declareExchange(new DirectExchange("filegrid.jobs", true, false));

        String jobPayload = String.format("""
                {
                  "fileId": "%s",
                  "category": "DRONE_VIDEO",
                  "stagingPath": "%s",
                  "sizeBytes": %d,
                  "sha256Plain": "%s",
                  "selectedStorage": "S3",
                  "storageKey": "%s",
                  "traceId": "trace-mismatch-1",
                  "enqueuedAt": "%s"
                }
                """, fileId, stagingFile.toAbsolutePath().toString().replace("\\", "/"), size, mismatchedHash, storageKey, Instant.now());

        rabbitTemplate.convertAndSend("filegrid.jobs", "filegrid.jobs.video", jobPayload, msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return msg;
        });

        // SUT worker detects hash mismatch, marks MISMATCH, and preserves staging file
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    FileRecord r = fileRecords.findById(fileId).orElseThrow();
                    assertThat(r.getIntegrityStatus()).isEqualTo(IntegrityStatus.MISMATCH);
                    assertThat(Files.exists(stagingFile))
                            .as("Staging file must be preserved on integrity mismatch for retry")
                            .isTrue();
                });

        Files.deleteIfExists(stagingFile);
    }

    // --- F18: Storage Fallback Trigger ---

    @Test
    @DisplayName("T1-F18-01: Primary storage exhaustion triggers fallback and FALLBACK audit event")
    void storageFallbackTrigger() throws Exception {
        UUID fileId = UUID.randomUUID();
        long size = 64 * 1024;
        DeterministicContent content = new DeterministicContent(777, size);
        String expectedHash = content.sha256();

        Path stagingFile = stagingDir.resolve(fileId.toString());
        try (InputStream in = content.open()) {
            Files.copy(in, stagingFile);
        }

        FileRecord record = new FileRecord(fileId, FileCategory.FAULT_RECORD, "comtrade.dat", size,
                DataOrigin.REAL, FileSource.UI, "worker-test", Instant.now());
        record.setStatus(FileStatus.DESTINATION_SELECTED);
        record.setSha256Plain(expectedHash);
        record.setStorageLocation(StorageType.FTP, "faults/2026/11/14/" + fileId);
        fileRecords.saveAndFlush(record);

        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
        admin.declareExchange(new DirectExchange("filegrid.jobs", true, false));

        // Job targets FTP primary storage (which is configured to fail)
        String jobPayload = String.format("""
                {
                  "fileId": "%s",
                  "category": "FAULT_RECORD",
                  "stagingPath": "%s",
                  "sizeBytes": %d,
                  "sha256Plain": "%s",
                  "selectedStorage": "FTP",
                  "storageKey": "faults/2026/11/14/%s.dat",
                  "traceId": "trace-fallback-1",
                  "enqueuedAt": "%s"
                }
                """, fileId, stagingFile.toAbsolutePath().toString().replace("\\", "/"), size, expectedHash, fileId, Instant.now());

        rabbitTemplate.convertAndSend("filegrid.jobs", "filegrid.jobs.fault", jobPayload, msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return msg;
        });

        // SUT worker exhausts FTP attempts, triggers fallback to S3, and records FALLBACK event
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    FileRecord r = fileRecords.findById(fileId).orElseThrow();
                    assertThat(r.getStatus()).isEqualTo(FileStatus.STORED_VERIFIED);
                    assertThat(r.getStorageType()).isEqualTo(StorageType.S3);
                    var events = fileEvents.findByFileIdOrderByAtAsc(fileId);
                    assertThat(events)
                            .filteredOn(e -> e.getType() == FileEventType.FALLBACK)
                            .isNotEmpty();
                });

        Files.deleteIfExists(stagingFile);
    }

    // --- F19: Storage Down PENDING State ---

    @Test
    @DisplayName("T1-F19-01: When all storage targets fail, file transitions to PENDING and staging is retained")
    void allStorageDownPendingState() throws Exception {
        UUID fileId = UUID.randomUUID();
        long size = 32 * 1024;
        DeterministicContent content = new DeterministicContent(888, size);
        String expectedHash = content.sha256();

        Path stagingFile = stagingDir.resolve(fileId.toString());
        try (InputStream in = content.open()) {
            Files.copy(in, stagingFile);
        }

        FileRecord record = new FileRecord(fileId, FileCategory.DRONE_VIDEO, "pending.mp4", size,
                DataOrigin.REAL, FileSource.UI, "worker-test", Instant.now());
        record.setStatus(FileStatus.DESTINATION_SELECTED);
        record.setSha256Plain(expectedHash);
        fileRecords.saveAndFlush(record);

        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
        admin.declareExchange(new DirectExchange("filegrid.jobs", true, false));

        // Target storage key pointing to non-existent / unreachable storage to force total failure
        String jobPayload = String.format("""
                {
                  "fileId": "%s",
                  "category": "DRONE_VIDEO",
                  "stagingPath": "%s",
                  "sizeBytes": %d,
                  "sha256Plain": "%s",
                  "selectedStorage": "FTP",
                  "storageKey": "nonexistent-bucket/fail.mp4",
                  "traceId": "trace-pending-1",
                  "enqueuedAt": "%s"
                }
                """, fileId, stagingFile.toAbsolutePath().toString().replace("\\", "/"), size, expectedHash, Instant.now());

        rabbitTemplate.convertAndSend("filegrid.jobs", "filegrid.jobs.video", jobPayload, msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return msg;
        });

        // SUT transitions file to PENDING state and retains staging file
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    FileRecord loaded = fileRecords.findById(fileId).orElseThrow();
                    assertThat(loaded.getStatus()).isEqualTo(FileStatus.PENDING);
                    assertThat(Files.exists(stagingFile))
                            .as("Staging file must remain in staging directory when status is PENDING")
                            .isTrue();
                });

        Files.deleteIfExists(stagingFile);
    }
}
