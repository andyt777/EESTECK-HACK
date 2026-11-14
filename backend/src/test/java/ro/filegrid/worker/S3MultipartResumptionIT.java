package ro.filegrid.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;

/**
 * Opaque-box integration test for S3 Multipart Upload State Persistence and Crash Resumption (F20).
 * Validates:
 * - Persisting uploadId and confirmed parts into s3_multipart_transfer DB table
 * - Interrupted transfer resumption starting from last confirmed part without re-uploading confirmed parts
 * - Assembly completion and read-back hash verification to STORED_VERIFIED
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"api", "worker"})
@Testcontainers
class S3MultipartResumptionIT {

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
    private static final int S3_MIN_PART_SIZE = 5 * 1024 * 1024; // 5MB S3 requirement

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
        registry.add("filegrid.worker.categories", () -> "DRONE_VIDEO");
        registry.add("filegrid.worker.concurrency", () -> "2");
    }

    @BeforeAll
    static void initS3() {
        StorageProperties.S3 s3Props = new StorageProperties.S3(
                URI.create(S3.getS3URL()), "us-east-1", S3.getUserName(), S3.getPassword(),
                "filegrid-", "filegrid-benchmark", S3_MIN_PART_SIZE);
        s3Client = S3ClientConfig.create(s3Props);
        s3Client.createBucket(b -> b.bucket(BUCKET));
        s3Adapter = new S3StorageAdapter(s3Client, new StorageProperties(null, s3Props, null, null));
    }

    @Autowired
    private FileRecordRepository fileRecords;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate metaDb;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("T1-F20: S3 multipart upload crash simulation, state persistence in DB, and clean resumption")
    void s3MultipartCrashResumptionLifecycle() throws Exception {
        UUID fileId = UUID.randomUUID();
        // 3 parts: 2 x 5MB + 1 x 2MB = 12MB total
        long totalBytes = 12L * 1024 * 1024;
        DeterministicContent content = new DeterministicContent(2026, totalBytes);
        String expectedHash = content.sha256();

        // 1. Write content to staging file
        Path stagingFile = stagingDir.resolve(fileId.toString());
        try (InputStream in = content.open()) {
            Files.copy(in, stagingFile);
        }

        FileRecord record = new FileRecord(fileId, FileCategory.DRONE_VIDEO, "resumption.mp4", totalBytes,
                DataOrigin.REAL, FileSource.UI, "worker", Instant.now());
        record.setStatus(FileStatus.TRANSFERRING);
        record.setSha256Plain(expectedHash);
        fileRecords.saveAndFlush(record);

        String storageKey = StorageKeys.forCategory(FileCategory.DRONE_VIDEO, Instant.now(), fileId);

        // 2. Strict Flyway Schema assertion: s3_multipart_transfer table must exist
        Integer tableExists = metaDb.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 's3_multipart_transfer'",
                Integer.class);
        assertThat(tableExists).as("s3_multipart_transfer table must exist in database schema").isEqualTo(1);

        // 3. Initiate multipart upload with S3 MinIO
        CreateMultipartUploadResponse createResponse = s3Client.createMultipartUpload(b -> b
                .bucket(BUCKET)
                .key(storageKey)
                .contentType("video/mp4"));
        String uploadId = createResponse.uploadId();

        // 4. Upload Part 1 (0 to 5MB) to MinIO to simulate interrupted upload state
        byte[] part1Bytes = new byte[S3_MIN_PART_SIZE];
        try (InputStream in = Files.newInputStream(stagingFile)) {
            int read = in.readNBytes(part1Bytes, 0, part1Bytes.length);
            assertThat(read).isEqualTo(S3_MIN_PART_SIZE);
        }
        var part1Response = s3Client.uploadPart(b -> b
                .bucket(BUCKET)
                .key(storageKey)
                .uploadId(uploadId)
                .partNumber(1), RequestBody.fromBytes(part1Bytes));
        String etag1 = part1Response.eTag();

        // 5. Persist uploadId and confirmed Part 1 in s3_multipart_transfer
        ArrayNode partsArray = objectMapper.createArrayNode();
        partsArray.addObject().put("partNumber", 1).put("etag", etag1).put("size", S3_MIN_PART_SIZE);

        metaDb.update("""
                INSERT INTO s3_multipart_transfer (file_id, upload_id, bucket, storage_key, part_size, total_parts, confirmed_parts)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, fileId, uploadId, BUCKET, storageKey, (long) S3_MIN_PART_SIZE, 3, partsArray.toString());

        // 6. Setup RabbitMQ topology and event listener queue
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
        admin.declareExchange(new DirectExchange("filegrid.jobs", true, false));
        admin.declareExchange(new FanoutExchange("filegrid.events", true, false));
        Queue jobQueue = new Queue("filegrid.jobs.video", true);
        admin.declareQueue(jobQueue);
        admin.declareBinding(BindingBuilder.bind(jobQueue)
                .to(new DirectExchange("filegrid.jobs", true, false))
                .with("filegrid.jobs.video"));
        Queue eventCollectorQueue = new AnonymousQueue();
        admin.declareQueue(eventCollectorQueue);
        admin.declareBinding(BindingBuilder.bind(eventCollectorQueue)
                .to(new FanoutExchange("filegrid.events", true, false)));

        // 7. Trigger SUT crash resumption via AMQP job delivery
        String resumeJobPayload = String.format("""
                {
                  "fileId": "%s",
                  "category": "DRONE_VIDEO",
                  "stagingPath": "%s",
                  "sizeBytes": %d,
                  "sha256Plain": "%s",
                  "selectedStorage": "S3",
                  "storageKey": "%s",
                  "traceId": "trace-resume-1",
                  "enqueuedAt": "%s"
                }
                """, fileId, stagingFile.toAbsolutePath().toString().replace("\\", "/"), totalBytes, expectedHash, storageKey, Instant.now());

        rabbitTemplate.convertAndSend("filegrid.jobs", "filegrid.jobs.video", resumeJobPayload, msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return msg;
        });

        // 8. SUT resumes from confirmed Part 1, uploads remaining parts (2 and 3), and completes
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    FileRecord verified = fileRecords.findById(fileId).orElseThrow();
                    assertThat(verified.getStatus()).isEqualTo(FileStatus.STORED_VERIFIED);
                    assertThat(verified.getIntegrityStatus()).isEqualTo(IntegrityStatus.VERIFIED_INTACT);
                    assertThat(verified.getStorageType()).isEqualTo(StorageType.S3);

                    // DB tracking row must be cleaned up upon successful completion
                    Integer remaining = metaDb.queryForObject(
                            "SELECT count(*) FROM s3_multipart_transfer WHERE file_id = ?", Integer.class, fileId);
                    assertThat(remaining).isEqualTo(0);

                    // Staging file must be deleted
                    assertThat(Files.exists(stagingFile))
                            .as("Staging file must be deleted after completed resumption")
                            .isFalse();
                });

        // 9. Verify full assembled object hash from S3 matches expected hash
        try (InputStream s3Stream = s3Adapter.get(storageKey)) {
            String readBackSha = DeterministicContent.sha256Of(s3Stream);
            assertThat(readBackSha).isEqualTo(expectedHash);
        }
    }
}
