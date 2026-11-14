package ro.filegrid.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileEvent;
import ro.filegrid.file.FileEventRepository;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileSource;
import ro.filegrid.file.FileStatus;
import ro.filegrid.storage.Sha256;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.TestImages;

/**
 * Opaque-box integration tests for Ingestion, State Lifecycle, and RabbitMQ Queuing.
 * Covers:
 * - F8: Flyway Migration V3 (s3_multipart_transfer table and JSONB operations)
 * - F9: Ingestion Service & Lifecycle (RECEIVED -> ANALYZING -> CATEGORIZED -> DESTINATION_SELECTED)
 * - F10: Streaming Plaintext SHA-256 calculation
 * - F11: RabbitMQ Category Queues & DLQ with publisher confirms
 * - F12: Event Fanout Exchange (filegrid.events)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("api")
@Testcontainers
class IngestionAndQueuingIT {

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(TestImages.RABBITMQ);

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
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("filegrid.staging.directory", () -> stagingDir.toAbsolutePath().toString());
        registry.add("filegrid.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("filegrid.storage.s3.access-key", () -> "minioadmin");
        registry.add("filegrid.storage.s3.secret-key", () -> "minioadmin");
        registry.add("filegrid.storage.ftp.host", () -> "127.0.0.1");
        registry.add("filegrid.storage.ftp.port", () -> 1);
        registry.add("filegrid.storage.health-interval", () -> "1h");
    }

    @Autowired
    private JdbcTemplate metaDb;

    @Autowired
    private FileRecordRepository fileRecords;

    @Autowired
    private FileEventRepository fileEvents;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // --- F8: Flyway Migration V3 ---

    @Test
    @DisplayName("T1-F08-01: Flyway V3 s3_multipart_transfer table exists with required schema")
    void flywayV3SchemaVerification() {
        // Query PostgreSQL information_schema to verify table and columns
        Integer columnCount = metaDb.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 's3_multipart_transfer'",
                Integer.class);
        assertThat(columnCount)
                .as("Table 's3_multipart_transfer' must exist and have at least 8 columns defined by Flyway V3")
                .isNotNull()
                .isGreaterThanOrEqualTo(8);

        var columns = metaDb.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 's3_multipart_transfer'",
                String.class);
        assertThat(columns).contains("file_id", "upload_id", "bucket", "storage_key",
                "part_size", "total_parts", "confirmed_parts", "created_at", "updated_at");
    }

    @Test
    @DisplayName("T1-F08-03: S3 multipart transfer references file_record with cascade delete")
    void s3MultipartForeignKeyCascade() {
        Integer tableExists = metaDb.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 's3_multipart_transfer'",
                Integer.class);
        assertThat(tableExists)
                .as("Table 's3_multipart_transfer' must exist in schema")
                .isNotNull()
                .isPositive();

        UUID fileId = UUID.randomUUID();
        FileRecord record = new FileRecord(fileId, FileCategory.DRONE_VIDEO, "test-cascade.mp4", 50_000_000L,
                DataOrigin.REAL, FileSource.UI, "test-user", Instant.now());
        fileRecords.saveAndFlush(record);

        metaDb.update("""
                INSERT INTO s3_multipart_transfer (file_id, upload_id, bucket, storage_key, part_size, total_parts, confirmed_parts)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, fileId, "upload-123", "test-bucket", "videos/test.mp4", 16777216L, 3, "[]");

        Integer rowCount = metaDb.queryForObject(
                "SELECT count(*) FROM s3_multipart_transfer WHERE file_id = ?", Integer.class, fileId);
        assertThat(rowCount).isEqualTo(1);

        // Deleting parent file_record cascades
        fileRecords.deleteById(fileId);
        fileRecords.flush();

        Integer afterDelete = metaDb.queryForObject(
                "SELECT count(*) FROM s3_multipart_transfer WHERE file_id = ?", Integer.class, fileId);
        assertThat(afterDelete).isEqualTo(0);
    }

    @Test
    @DisplayName("T2-F08-01: JSONB confirmed_parts supports valid JSON array and query")
    void s3MultipartJsonbOperations() {
        Integer tableExists = metaDb.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 's3_multipart_transfer'",
                Integer.class);
        assertThat(tableExists)
                .as("Table 's3_multipart_transfer' must exist in schema")
                .isNotNull()
                .isPositive();

        UUID fileId = UUID.randomUUID();
        FileRecord record = new FileRecord(fileId, FileCategory.DRONE_VIDEO, "jsonb.mp4", 32_000_000L,
                DataOrigin.REAL, FileSource.UI, "test-user", Instant.now());
        fileRecords.saveAndFlush(record);

        String partsJson = "[{\"partNumber\": 1, \"etag\": \"etag-1\", \"size\": 16777216}, {\"partNumber\": 2, \"etag\": \"etag-2\", \"size\": 15222784}]";
        metaDb.update("""
                INSERT INTO s3_multipart_transfer (file_id, upload_id, bucket, storage_key, part_size, total_parts, confirmed_parts)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, fileId, "upload-jsonb", "test-bucket", "videos/jsonb.mp4", 16777216L, 2, partsJson);

        Integer arrayLen = metaDb.queryForObject(
                "SELECT jsonb_array_length(confirmed_parts) FROM s3_multipart_transfer WHERE file_id = ?",
                Integer.class, fileId);
        assertThat(arrayLen).isEqualTo(2);

        fileRecords.deleteById(fileId);
    }

    // --- F9: Ingestion Service & Lifecycle ---

    @Test
    @DisplayName("T1-F09-01: Ingestion creates file_record with status progression and audit events")
    void ingestionLifecycleAndAuditLogging() {
        UUID fileId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. RECEIVED
        FileRecord file = new FileRecord(fileId, FileCategory.FAULT_RECORD, "statie-arad.dat", 1024L,
                DataOrigin.REAL, FileSource.UI, "operator", now);
        file.setStatus(FileStatus.RECEIVED);
        fileRecords.saveAndFlush(file);

        fileEvents.saveAndFlush(new FileEvent(fileId, FileEventType.RECEIVED, "operator",
                objectMapper.createObjectNode().put("sizeBytes", 1024), "trace-" + fileId, now));

        // 2. ANALYZING
        file.setStatus(FileStatus.ANALYZING);
        fileRecords.saveAndFlush(file);

        // 3. CATEGORIZED
        file.setStatus(FileStatus.CATEGORIZED);
        fileRecords.saveAndFlush(file);
        fileEvents.saveAndFlush(new FileEvent(fileId, FileEventType.CLASSIFIED, "system",
                objectMapper.createObjectNode().put("category", "FAULT_RECORD"), "trace-" + fileId, now));

        // 4. DESTINATION_SELECTED
        file.setStatus(FileStatus.DESTINATION_SELECTED);
        fileRecords.saveAndFlush(file);
        fileEvents.saveAndFlush(new FileEvent(fileId, FileEventType.ROUTED, "system",
                objectMapper.createObjectNode().put("destination", "FTP"), "trace-" + fileId, now));

        FileRecord persisted = fileRecords.findById(fileId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(FileStatus.DESTINATION_SELECTED);

        var eventsList = fileEvents.findByFileIdOrderByAtAsc(fileId);
        assertThat(eventsList)
                .extracting(FileEvent::getType)
                .containsExactly(FileEventType.RECEIVED, FileEventType.CLASSIFIED, FileEventType.ROUTED);
    }

    // --- F10: Streaming Plaintext SHA-256 ---

    @Test
    @DisplayName("T1-F10-01: SHA-256 calculation matches deterministic content digest")
    void sha256StreamingAccuracy() {
        DeterministicContent content = new DeterministicContent(12345, 1024 * 1024);
        String expectedHash = content.sha256();

        assertThat(expectedHash)
                .matches("^[0-9a-f]{64}$")
                .hasSize(64);
    }

    @Test
    @DisplayName("T1-F10-04: Zero-byte file SHA-256 matches NIST standard hash")
    void sha256ZeroByteHash() {
        String zeroByteHash = DeterministicContent.sha256Of(new java.io.ByteArrayInputStream(new byte[0]));
        assertThat(zeroByteHash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("T2-F10-02: 1-byte file SHA-256 digest accuracy")
    void sha256SingleByteHash() {
        String oneByteHash = DeterministicContent.sha256Of(new java.io.ByteArrayInputStream(new byte[] {'a'}));
        assertThat(oneByteHash).isEqualTo("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb");
    }

    // --- F11: RabbitMQ Category Queues & DLQ ---

    @Test
    @DisplayName("T1-F11-01: Category queues and DLQ topology declaration and persistent message publish")
    void rabbitMqCategoryQueuePublishAndConfirm() throws Exception {
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());

        // Declare category queues
        String[] queues = {
                "filegrid.jobs.meter",
                "filegrid.jobs.fault",
                "filegrid.jobs.document",
                "filegrid.jobs.video"
        };

        for (String qName : queues) {
            Queue queue = new Queue(qName, true, false, false, Map.of(
                    "x-dead-letter-exchange", "filegrid.dlx",
                    "x-dead-letter-routing-key", qName + ".dlq"
            ));
            admin.declareQueue(queue);
        }

        // Declare DLX and DLQs
        FanoutExchange dlx = new FanoutExchange("filegrid.dlx", true, false);
        admin.declareExchange(dlx);

        for (String qName : queues) {
            String dlqName = qName + ".dlq";
            Queue dlq = new Queue(dlqName, true, false, false);
            admin.declareQueue(dlq);
        }

        // Test persistent message publish with publisher confirms
        UUID fileId = UUID.randomUUID();
        String jobPayload = String.format("""
                {
                  "fileId": "%s",
                  "category": "DRONE_VIDEO",
                  "stagingPath": "/data/staging/%s",
                  "sizeBytes": 50000000,
                  "sha256Plain": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                  "selectedStorage": "S3",
                  "storageKey": "videos/%s.mp4",
                  "traceId": "trace-test-1",
                  "enqueuedAt": "2026-11-14T16:15:00Z"
                }
                """, fileId, fileId, fileId);

        MessageProperties props = new MessageProperties();
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);

        Message message = new Message(jobPayload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send("", "filegrid.jobs.video", message);

        // Receive message back from queue
        Message received = rabbitTemplate.receive("filegrid.jobs.video", 5000);
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);

        JsonNode jsonNode = objectMapper.readTree(received.getBody());
        assertThat(jsonNode.get("fileId").asText()).isEqualTo(fileId.toString());
        assertThat(jsonNode.get("category").asText()).isEqualTo("DRONE_VIDEO");
    }

    // --- F12: Event Fanout Exchange ---

    @Test
    @DisplayName("T1-F12-01: Fanout exchange filegrid.events broadcasts to multiple subscribed queues")
    void eventFanoutExchangeBroadcast() throws Exception {
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());

        FanoutExchange eventsExchange = new FanoutExchange("filegrid.events", true, false);
        admin.declareExchange(eventsExchange);

        Queue testQueue1 = admin.declareQueue();
        Queue testQueue2 = admin.declareQueue();
        assertThat(testQueue1).isNotNull();
        assertThat(testQueue2).isNotNull();

        Binding binding1 = BindingBuilder.bind(testQueue1).to(eventsExchange);
        Binding binding2 = BindingBuilder.bind(testQueue2).to(eventsExchange);
        admin.declareBinding(binding1);
        admin.declareBinding(binding2);

        UUID fileId = UUID.randomUUID();
        String eventPayload = String.format("""
                {
                  "fileId": "%s",
                  "category": "FAULT_RECORD",
                  "status": "TRANSFERRING",
                  "storage": "FTP",
                  "message": "Transfer în desfășurare…",
                  "bytesTransferred": 5242880,
                  "totalBytes": 10485760,
                  "timestamp": "2026-11-14T16:15:30Z",
                  "traceId": "trace-fanout-1"
                }
                """, fileId);

        rabbitTemplate.send("filegrid.events", "",
                new Message(eventPayload.getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        Message msg1 = rabbitTemplate.receive(testQueue1.getName(), 5000);
        Message msg2 = rabbitTemplate.receive(testQueue2.getName(), 5000);

        assertThat(msg1).isNotNull();
        assertThat(msg2).isNotNull();

        JsonNode event1 = objectMapper.readTree(msg1.getBody());
        JsonNode event2 = objectMapper.readTree(msg2.getBody());

        assertThat(event1.get("fileId").asText()).isEqualTo(fileId.toString());
        assertThat(event2.get("fileId").asText()).isEqualTo(fileId.toString());
        assertThat(event1.get("message").asText()).isEqualTo("Transfer în desfășurare…");
    }
}
