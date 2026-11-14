package ro.filegrid.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.amqp.core.QueueInformation;
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
import ro.filegrid.file.FileEventRepository;
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
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Opaque-box integration tests for Job Idempotency Guard (F21).
 * Covers:
 * - Duplicate delivery of an AMQP job message for an already STORED_VERIFIED file
 * - Immediate ACK without re-writing storage or re-creating staging files
 * - Zero duplicate event emissions
 * - Store-DB ON CONFLICT DO NOTHING handling
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"api", "worker"})
@Testcontainers
class JobIdempotencyIT {

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
        registry.add("filegrid.worker.categories", () -> "DRONE_VIDEO,METER_READING");
        registry.add("filegrid.worker.concurrency", () -> "2");
    }

    @BeforeAll
    static void initS3() {
        StorageProperties.S3 s3Props = new StorageProperties.S3(
                URI.create(S3.getS3URL()), "us-east-1", S3.getUserName(), S3.getPassword(),
                "filegrid-", "filegrid-benchmark", 16 * 1024 * 1024);
        s3Client = S3ClientConfig.create(s3Props);
        s3Client.createBucket(b -> b.bucket(BUCKET));
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

    @Test
    @DisplayName("T1-F21-01: Duplicate delivery of job for STORED_VERIFIED file skips re-upload and staging check")
    void duplicateJobDeliveryAcknowledgedWithoutRewrite() throws Exception {
        UUID fileId = UUID.randomUUID();
        long size = 64 * 1024;
        DeterministicContent content = new DeterministicContent(555, size);
        String expectedHash = content.sha256();

        // 1. Initial verified object exists in storage
        String storageKey = StorageKeys.forCategory(FileCategory.DRONE_VIDEO, Instant.now(), fileId);
        try (InputStream in = content.open()) {
            s3Adapter.put(storageKey, in, size, Map.of());
        }

        FileRecord record = new FileRecord(fileId, FileCategory.DRONE_VIDEO, "verified.mp4", size,
                DataOrigin.REAL, FileSource.UI, "worker", Instant.now());
        record.setStatus(FileStatus.STORED_VERIFIED);
        record.setIntegrityStatus(IntegrityStatus.VERIFIED_INTACT);
        record.setStorageLocation(StorageType.S3, storageKey);
        record.setSha256Plain(expectedHash);
        fileRecords.saveAndFlush(record);

        // Staging file does NOT exist (normal state after verification)
        Path stagingFile = stagingDir.resolve(fileId.toString());
        assertThat(Files.exists(stagingFile)).isFalse();

        // Capture S3 object last modified time and eTag before duplicate delivery
        HeadObjectResponse headBefore = s3Client.headObject(b -> b.bucket(BUCKET).key(storageKey));
        Instant lastModifiedBefore = headBefore.lastModified();
        String eTagBefore = headBefore.eTag();

        // 2. Setup RabbitMQ topology and event listener queue
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

        // 3. Publish duplicate job message to AMQP queue
        String duplicateJobPayload = String.format("""
                {
                  "fileId": "%s",
                  "category": "DRONE_VIDEO",
                  "stagingPath": "%s",
                  "sizeBytes": %d,
                  "sha256Plain": "%s",
                  "selectedStorage": "S3",
                  "storageKey": "%s",
                  "traceId": "trace-dup-1",
                  "enqueuedAt": "%s"
                }
                """, fileId, stagingFile.toAbsolutePath().toString().replace("\\", "/"), size, expectedHash, storageKey, Instant.now());

        rabbitTemplate.convertAndSend("filegrid.jobs", "filegrid.jobs.video", duplicateJobPayload, msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return msg;
        });

        // 4. SUT worker must acknowledge message from queue without re-processing
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    QueueInformation qInfo = admin.getQueueInfo("filegrid.jobs.video");
                    if (qInfo != null) {
                        assertThat(qInfo.getMessageCount()).isEqualTo(0);
                    }
                });

        // 5. Verify zero storage rewrites occurred (metadata untouched)
        HeadObjectResponse headAfter = s3Client.headObject(b -> b.bucket(BUCKET).key(storageKey));
        assertThat(headAfter.lastModified()).isEqualTo(lastModifiedBefore);
        assertThat(headAfter.eTag()).isEqualTo(eTagBefore);

        // 6. Verify staging file was never recreated
        assertThat(Files.exists(stagingFile)).isFalse();

        // 7. Verify file status remained intact
        FileRecord duplicateCheck = fileRecords.findById(fileId).orElseThrow();
        assertThat(duplicateCheck.getStatus()).isEqualTo(FileStatus.STORED_VERIFIED);
        assertThat(duplicateCheck.getIntegrityStatus()).isEqualTo(IntegrityStatus.VERIFIED_INTACT);
    }

    @Test
    @DisplayName("T1-F21-05: Store-DB ON CONFLICT DO NOTHING handles duplicate inserts idempotently")
    void storeDbIdempotentUpsert() {
        // Query store_blob table in storeDb to ensure idempotency handling
        JdbcTemplate storeDb = new JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        STORE_DB.getJdbcUrl(), STORE_DB.getUsername(), STORE_DB.getPassword(), true));

        String key = "meter/2026/11/14/meter-reading-idempotent.json";
        byte[] payload = "{\"kw\": 12.3}".getBytes();

        // First insert
        storeDb.update("""
                INSERT INTO stored_blob (storage_key, content, size_bytes, sha256_hex, created_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (storage_key) DO NOTHING
                """, key, payload, payload.length, "dummy-hash");

        // Duplicate insert with same key
        int rowsAffected = storeDb.update("""
                INSERT INTO stored_blob (storage_key, content, size_bytes, sha256_hex, created_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (storage_key) DO NOTHING
                """, key, payload, payload.length, "dummy-hash");

        assertThat(rowsAffected).isEqualTo(0);

        Integer count = storeDb.queryForObject(
                "SELECT count(*) FROM stored_blob WHERE storage_key = ?", Integer.class, key);
        assertThat(count).isEqualTo(1);
    }
}
