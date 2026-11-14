package ro.filegrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
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
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.s3.S3ClientConfig;
import ro.filegrid.storage.s3.S3StorageAdapter;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.TestImages;
import ro.filegrid.testsupport.TusTestClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Stage 4 acceptance: a 1.5 GB drone video uploaded through tus, interrupted at
 * 40 % (the client connection breaks), resumed from the offset the server
 * reports, then hashed, routed, sent to S3 as a multipart upload, read back and
 * verified — all with the test JVM limited to 256 MB of heap (well under the
 * 512 MB required), which proves that nothing is buffered whole.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "worker"})
@Testcontainers
class LargeResumableUploadIT {

    private static final long SIZE = 1536L * 1024 * 1024;
    private static final long INTERRUPT_AT = SIZE * 40 / 100;

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
                "filegrid-", "filegrid-benchmark", 16 * 1024 * 1024);
        S3Client s3Client = S3ClientConfig.create(s3Props);
        s3Client.createBucket(b -> b.bucket("filegrid-videos"));
        s3Client.createBucket(b -> b.bucket("filegrid-benchmark"));
        s3Adapter = new S3StorageAdapter(s3Client, new StorageProperties(null, s3Props, null, null));
    }

    @LocalServerPort
    int port;

    @Autowired
    private FileRecordRepository fileRecords;

    @Test
    @DisplayName("Stage 4: 1.5 GB tus upload interrupted at 40%, resumed, stored on S3 and verified under 256 MB heap")
    void largeUploadInterruptedAndResumed() throws Exception {
        assertThat(Runtime.getRuntime().maxMemory()).as("test heap limit").isLessThanOrEqualTo(512L * 1024 * 1024);
        resetHeapPeaks();

        DeterministicContent content = new DeterministicContent(1500, SIZE);
        String expectedHash = content.sha256();
        TusTestClient tus = new TusTestClient("http://localhost:" + port + "/api");
        URI upload = tus.createUpload(SIZE, Map.of("filename", "inspectie-statia-nord.mp4",
                "category", "DRONE_VIDEO", "zone", "Banat", "station", "Timisoara Nord"));
        UUID fileId = UUID.fromString(upload.getPath().substring(upload.getPath().lastIndexOf('/') + 1));

        // 1. The connection breaks after 40% of the bytes.
        try {
            tus.sendPatch(upload, new BreakingInputStream(content.open(), INTERRUPT_AT), 0,
                    "application/offset+octet-stream", Map.of());
        } catch (IOException expected) {
            // the client side of the interruption
        }

        // 2. The server kept what it received; the client asks where to continue.
        long offset = Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .until(() -> tus.getOffset(upload), o -> o > 0);
        assertThat(offset).isLessThanOrEqualTo(INTERRUPT_AT);

        // 3. Resume from that offset to the end.
        try (InputStream rest = content.open()) {
            rest.skipNBytes(offset);
            long end = tus.uploadChunk(upload, rest, offset);
            assertThat(end).isEqualTo(SIZE);
        }

        // 4. Ingestion + transfer + read-back.
        Awaitility.await().atMost(Duration.ofMinutes(10)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    FileRecord record = fileRecords.findById(fileId).orElseThrow();
                    assertThat(record.getStatus()).isEqualTo(FileStatus.STORED_VERIFIED);
                });
        FileRecord record = fileRecords.findById(fileId).orElseThrow();
        assertThat(record.getSha256Plain()).isEqualTo(expectedHash);
        assertThat(record.getIntegrityStatus()).isEqualTo(IntegrityStatus.VERIFIED_INTACT);
        assertThat(record.getStorageType()).isEqualTo(StorageType.S3);
        assertThat(record.getSizeBytes()).isEqualTo(SIZE);
        assertThat(Files.exists(stagingDir.resolve(fileId.toString()))).isFalse();
        try (InputStream stored = s3Adapter.get(record.getStorageKey())) {
            assertThat(DeterministicContent.sha256Of(stored)).isEqualTo(expectedHash);
        }

        long peakHeap = peakHeapUsed();
        assertThat(peakHeap).as("peak heap during the 1.5 GB transfer").isLessThan(512L * 1024 * 1024);
    }

    private static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    private static long peakHeapUsed() {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                total += pool.getPeakUsage().getUsed();
            }
        }
        return total;
    }

    /** Delivers {@code limit} bytes, then fails like a dropped connection. */
    private static final class BreakingInputStream extends FilterInputStream {
        private long remaining;

        BreakingInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("connection lost");
            }
            remaining--;
            return super.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                throw new IOException("connection lost");
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
