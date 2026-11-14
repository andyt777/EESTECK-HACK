package ro.filegrid.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
import ro.filegrid.file.FileEvent;
import ro.filegrid.file.FileEventRepository;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileSource;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.IntegrityStatus;
import ro.filegrid.messaging.JobPublisher;
import ro.filegrid.messaging.TransferJob;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageProperties;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.s3.S3ClientConfig;
import ro.filegrid.storage.s3.S3StorageAdapter;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.TestImages;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Stage 4 acceptance: S3 stops in the middle of a video transfer. The worker
 * retries, falls back to FTP (also down here), parks the file as PENDING with
 * its staged copy intact; when S3 is back the recovery job re-routes it and
 * the transfer continues from the last confirmed multipart part to
 * STORED_VERIFIED, with no manual action.
 *
 * <p>S3 keeps a fixed host port so that the stopped container comes back at
 * the same address.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"api", "worker"})
@Testcontainers
class S3OutageRecoveryIT {

    private static final int S3_HOST_PORT = freePort();
    private static final int CONSOLE_HOST_PORT = freePort();
    private static final long SIZE = 400L * 1024 * 1024;

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(TestImages.RABBITMQ);

    @Container
    static final MinIOContainer S3 = new MinIOContainer(TestImages.S3)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(S3_HOST_PORT), new ExposedPort(9000)),
                    new PortBinding(Ports.Binding.bindPort(CONSOLE_HOST_PORT), new ExposedPort(9001))));

    @TempDir
    static Path stagingDir;

    private static S3Client s3Client;
    private static S3StorageAdapter s3Adapter;

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
        registry.add("filegrid.storage.s3.endpoint", () -> "http://127.0.0.1:" + S3_HOST_PORT);
        registry.add("filegrid.storage.s3.access-key", S3::getUserName);
        registry.add("filegrid.storage.s3.secret-key", S3::getPassword);
        registry.add("filegrid.storage.ftp.host", () -> "127.0.0.1");
        registry.add("filegrid.storage.ftp.port", () -> 1);
        registry.add("filegrid.storage.ftp.connect-timeout", () -> "2s");
        registry.add("filegrid.storage.health-interval", () -> "1s");
        registry.add("filegrid.ingestion.recovery-interval", () -> "2s");
        registry.add("filegrid.policy.file", () -> "src/test/resources/policy/fast-retry-policies.yaml");
        registry.add("filegrid.worker.categories", () -> "DRONE_VIDEO");
        registry.add("filegrid.worker.concurrency", () -> "1");
    }

    @BeforeAll
    static void initS3() {
        StorageProperties.S3 s3Props = new StorageProperties.S3(
                URI.create("http://127.0.0.1:" + S3_HOST_PORT), "us-east-1", S3.getUserName(), S3.getPassword(),
                "filegrid-", "filegrid-benchmark", 16 * 1024 * 1024);
        s3Client = S3ClientConfig.create(s3Props);
        s3Client.createBucket(b -> b.bucket("filegrid-videos"));
        s3Client.createBucket(b -> b.bucket("filegrid-benchmark"));
        s3Adapter = new S3StorageAdapter(s3Client, new StorageProperties(null, s3Props, null, null));
    }

    @Autowired
    private FileRecordRepository fileRecords;

    @Autowired
    private FileEventRepository fileEvents;

    @Autowired
    private JobPublisher jobs;

    @Test
    @DisplayName("Stage 4: S3 stopped during a transfer -> PENDING with staging kept -> S3 back -> resumed -> STORED_VERIFIED")
    void s3OutageDuringTransferRecoversAutomatically() throws Exception {
        UUID fileId = UUID.randomUUID();
        DeterministicContent content = new DeterministicContent(4004, SIZE);
        String expectedHash = content.sha256();
        Path stagingFile = stagingDir.resolve(fileId.toString());
        try (InputStream in = content.open()) {
            Files.copy(in, stagingFile);
        }
        Instant receivedAt = Instant.now();
        String key = StorageKeys.forCategory(FileCategory.DRONE_VIDEO, receivedAt, fileId);
        FileRecord record = new FileRecord(fileId, FileCategory.DRONE_VIDEO, "inspectie.mp4", SIZE,
                DataOrigin.REAL, FileSource.UI, "demo-user", receivedAt);
        record.setStatus(FileStatus.DESTINATION_SELECTED);
        record.setSha256Plain(expectedHash);
        record.setStorageLocation(StorageType.S3, key);
        record.setTraceId("trace-s3-outage");
        fileRecords.saveAndFlush(record);

        jobs.publish(new TransferJob(fileId, FileCategory.DRONE_VIDEO, stagingFile.toString(), SIZE, expectedHash,
                StorageType.S3, key, "trace-s3-outage", Instant.now()));

        // 1. Wait until at least one multipart part is confirmed, then stop S3.
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(50))
                .until(() -> confirmedParts(fileId) >= 1);
        S3.getDockerClient().stopContainerCmd(S3.getContainerId()).withTimeout(0).exec();

        // 2. Retries, fallback to FTP (down too), PENDING; the staged copy stays.
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(fileRecords.findById(fileId).orElseThrow().getStatus())
                        .isEqualTo(FileStatus.PENDING));
        assertThat(Files.exists(stagingFile)).isTrue();
        assertThat(eventTypes(fileId)).contains(FileEventType.FALLBACK);

        // 3. S3 is back: the recovery job re-routes the file and the transfer resumes.
        S3.getDockerClient().startContainerCmd(S3.getContainerId()).exec();
        Awaitility.await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    FileRecord verified = fileRecords.findById(fileId).orElseThrow();
                    assertThat(verified.getStatus()).isEqualTo(FileStatus.STORED_VERIFIED);
                    assertThat(verified.getIntegrityStatus()).isEqualTo(IntegrityStatus.VERIFIED_INTACT);
                    assertThat(verified.getStorageType()).isEqualTo(StorageType.S3);
                });
        assertThat(Files.exists(stagingFile)).isFalse();
        try (InputStream stored = s3Adapter.get(fileRecords.findById(fileId).orElseThrow().getStorageKey())) {
            assertThat(DeterministicContent.sha256Of(stored)).isEqualTo(expectedHash);
        }
        // The second transfer continued the multipart upload instead of starting over.
        assertThat(eventTypes(fileId)).contains(FileEventType.TRANSFER_RESUMED, FileEventType.ROUTED,
                FileEventType.STORED, FileEventType.VERIFIED);
    }

    private int confirmedParts(UUID fileId) {
        Integer parts = metaDbCount(fileId);
        return parts == null ? 0 : parts;
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate metaDb;

    private Integer metaDbCount(UUID fileId) {
        List<Integer> rows = metaDb.queryForList(
                "SELECT jsonb_array_length(confirmed_parts) FROM s3_multipart_transfer WHERE file_id = ?",
                Integer.class, fileId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<FileEventType> eventTypes(UUID fileId) {
        return fileEvents.findByFileIdOrderByAtAsc(fileId).stream().map(FileEvent::getType).toList();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
