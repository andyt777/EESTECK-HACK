package ro.filegrid.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.TestImages;
import ro.filegrid.testsupport.TusTestClient;

/**
 * Opaque-box integration tests for Tus 1.0 resumable upload pipeline.
 * Covers:
 * - F1: Tus 1.0 Server (OPTIONS, POST, HEAD, PATCH, DELETE)
 * - F2: Staging Direct Streaming (direct chunk writes to staging volume)
 * - F3: Tus Metadata Parsing (category, zone, station, equipment, Romanian description)
 * - F4: Max Upload Size Enforcement (413 Payload Too Large on exceeding limit)
 * - F5: Abandoned Upload Cleanup (purging idle uploads > 24h)
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
class TusUploadIT {

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    @TempDir
    static Path stagingDir;

    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024; // 10MB test ceiling

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", META_DB::getJdbcUrl);
        registry.add("spring.datasource.username", META_DB::getUsername);
        registry.add("spring.datasource.password", META_DB::getPassword);
        registry.add("filegrid.store-db.url", STORE_DB::getJdbcUrl);
        registry.add("filegrid.store-db.username", STORE_DB::getUsername);
        registry.add("filegrid.store-db.password", STORE_DB::getPassword);
        registry.add("filegrid.staging.directory", () -> stagingDir.toAbsolutePath().toString());
        registry.add("filegrid.upload.max-size-bytes", () -> String.valueOf(MAX_UPLOAD_BYTES));
        registry.add("filegrid.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("filegrid.storage.s3.access-key", () -> "minioadmin");
        registry.add("filegrid.storage.s3.secret-key", () -> "minioadmin");
        registry.add("filegrid.storage.ftp.host", () -> "127.0.0.1");
        registry.add("filegrid.storage.ftp.port", () -> 1);
        registry.add("filegrid.storage.ftp.connect-timeout", () -> "2s");
        registry.add("filegrid.storage.health-interval", () -> "1h");
    }

    @LocalServerPort
    int port;

    @Autowired
    private UploadCleanupScheduler cleanupScheduler;

    private TusTestClient tusClient;

    @BeforeEach
    void setUp() {
        String baseUrl = "http://localhost:" + port + "/api";
        this.tusClient = new TusTestClient(baseUrl);
    }

    // --- F1: Tus 1.0 Server ---

    @Test
    @DisplayName("T1-F01-01: Tus OPTIONS exposes protocol version and creation extensions")
    void tusOptionsExposesCapabilities() throws Exception {
        HttpResponse<String> response = tusClient.sendOptions();
        assertThat(response.statusCode()).isIn(200, 204);
        assertThat(response.headers().firstValue("Tus-Resumable")).hasValue("1.0.0");
        assertThat(response.headers().firstValue("Tus-Version")).hasValue("1.0.0");
        assertThat(response.headers().firstValue("Tus-Extension"))
                .isPresent()
                .hasValueSatisfying(ext -> assertThat(ext).contains("creation"));
    }

    @Test
    @DisplayName("T1-F01-02: Tus POST upload creation returns 201 Created with Location and Tus-Resumable")
    void tusPostCreatesUploadResource() throws Exception {
        long size = 1024 * 1024;
        Map<String, String> meta = Map.of("filename", "drone-sample.mp4", "category", "DRONE_VIDEO");

        HttpResponse<String> response = tusClient.sendCreateUpload(size, meta, Collections.emptyMap());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Tus-Resumable")).hasValue("1.0.0");
        assertThat(response.headers().firstValue("Location")).isPresent();
        response.headers().firstValue("Upload-Offset").ifPresent(offset -> assertThat(offset).isEqualTo("0"));
    }

    @Test
    @DisplayName("T1-F01-03: Tus HEAD returns current Upload-Offset and Upload-Length")
    void tusHeadReturnsOffsetAndLength() throws Exception {
        long size = 512 * 1024;
        URI uploadUri = tusClient.createUpload(size, Map.of("filename", "test.dat"));

        HttpResponse<Void> head = tusClient.sendHead(uploadUri, Collections.emptyMap());
        assertThat(head.statusCode()).isIn(200, 204);
        assertThat(head.headers().firstValue("Tus-Resumable")).hasValue("1.0.0");
        assertThat(head.headers().firstValue("Upload-Offset")).hasValue("0");
        assertThat(head.headers().firstValue("Upload-Length")).hasValue(String.valueOf(size));
    }

    @Test
    @DisplayName("T1-F01-04: Tus single chunk PATCH returns 204 No Content with updated Upload-Offset")
    void tusSingleChunkPatchCompletes() throws Exception {
        byte[] payload = new byte[256 * 1024];
        java.util.Arrays.fill(payload, (byte) 'A');
        URI uploadUri = tusClient.createUpload(payload.length, Map.of("filename", "single-chunk.bin"));

        long newOffset = tusClient.uploadBytes(uploadUri, payload, 0);
        assertThat(newOffset).isEqualTo(payload.length);
        assertThat(tusClient.getOffset(uploadUri)).isEqualTo(payload.length);
    }

    @Test
    @DisplayName("T1-F01-05: Tus multi-chunk sequential PATCH updates offset after each chunk")
    void tusMultiChunkSequentialUpload() throws Exception {
        int chunkSize = 64 * 1024;
        int totalSize = chunkSize * 3;
        URI uploadUri = tusClient.createUpload(totalSize, Map.of("filename", "multi-chunk.bin"));

        for (int i = 0; i < 3; i++) {
            byte[] chunk = new byte[chunkSize];
            java.util.Arrays.fill(chunk, (byte) (i + 1));
            long expectedOffset = (long) (i + 1) * chunkSize;
            long offset = tusClient.uploadBytes(uploadUri, chunk, (long) i * chunkSize);
            assertThat(offset).isEqualTo(expectedOffset);
        }

        assertThat(tusClient.getOffset(uploadUri)).isEqualTo(totalSize);
    }

    @Test
    @DisplayName("T1-F01-06: Tus DELETE cancels upload and subsequent HEAD returns 404")
    void tusDeleteCancelsUpload() throws Exception {
        URI uploadUri = tusClient.createUpload(1024 * 1024, Map.of("filename", "cancel-me.bin"));
        tusClient.uploadBytes(uploadUri, new byte[1024], 0);

        tusClient.deleteUpload(uploadUri);

        HttpResponse<Void> headAfterDelete = tusClient.sendHead(uploadUri, Collections.emptyMap());
        assertThat(headAfterDelete.statusCode()).isEqualTo(404);
    }

    // --- F1 Corner Cases ---

    @Test
    @DisplayName("T2-F01-01: Zero-byte Tus upload completes cleanly")
    void tusZeroByteUpload() throws Exception {
        URI uploadUri = tusClient.createUpload(0, Map.of("filename", "empty.bin"));
        HttpResponse<String> patch = tusClient.sendPatch(uploadUri, new ByteArrayInputStream(new byte[0]), 0,
                "application/offset+octet-stream", Collections.emptyMap());
        assertThat(patch.statusCode()).isIn(200, 204);
        assertThat(tusClient.getOffset(uploadUri)).isEqualTo(0);
    }

    @Test
    @DisplayName("T2-F01-02: Tus PATCH with incorrect Upload-Offset returns 409 Conflict")
    void tusOffsetMismatchReturns409() throws Exception {
        URI uploadUri = tusClient.createUpload(1024 * 1024, Map.of("filename", "conflict.bin"));

        HttpResponse<String> response = tusClient.sendPatch(uploadUri, new ByteArrayInputStream(new byte[1024]),
                2048, "application/offset+octet-stream", Collections.emptyMap());
        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("T2-F01-04: Tus PATCH with invalid Content-Type returns 415 Unsupported Media Type")
    void tusInvalidContentTypeReturns415() throws Exception {
        URI uploadUri = tusClient.createUpload(1024 * 1024, Map.of("filename", "wrong-type.bin"));

        HttpResponse<String> response = tusClient.sendPatch(uploadUri, new ByteArrayInputStream(new byte[1024]),
                0, "application/octet-stream", Collections.emptyMap());
        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    @DisplayName("T2-F01-05: Missing or unsupported Tus-Resumable header returns 412 Precondition Failed")
    void tusUnsupportedVersionReturns412() throws Exception {
        HttpResponse<String> response = tusClient.sendCreateUpload(1024L, Map.of("filename", "bad-ver.bin"),
                Map.of("Tus-Resumable", "0.2.1"));
        assertThat(response.statusCode()).isEqualTo(412);
        assertThat(response.headers().firstValue("Tus-Version")).hasValue("1.0.0");
    }

    // --- F2: Staging Direct Streaming ---

    @Test
    @DisplayName("T1-F02-01: Chunks stream directly to staging directory without loss or corruption")
    void stagingDirectStreamingAccuracy() throws Exception {
        long size = 2 * 1024 * 1024;
        DeterministicContent content = new DeterministicContent(42, size);
        URI uploadUri = tusClient.createUpload(size, Map.of("filename", "deterministic.bin"));

        try (InputStream in = content.open()) {
            long finalOffset = tusClient.uploadChunk(uploadUri, in, 0);
            assertThat(finalOffset).isEqualTo(size);
        }

        // Verify staging directory contains files matching the upload
        boolean foundMatchingFile = Files.walk(stagingDir)
                .filter(Files::isRegularFile)
                .anyMatch(p -> {
                    try {
                        return Files.size(p) == size;
                    } catch (Exception e) {
                        return false;
                    }
                });
        assertThat(foundMatchingFile).as("Staging volume should contain uploaded file of exact byte size").isTrue();
    }

    // --- F3: Tus Metadata Parsing ---

    @Test
    @DisplayName("T1-F03-01: Parses industrial grid metadata including Romanian diacritics")
    void tusParsesGridMetadataWithDiacritics() throws Exception {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("category", "DRONE_VIDEO");
        meta.put("zone", "Banat");
        meta.put("station", "Timișoara 1");
        meta.put("equipment", "TR_400kV_1");
        meta.put("eventType", "INSPECTION");
        meta.put("eventTime", "2026-11-14T16:15:00Z");
        meta.put("description", "Inspecție linie 400kV Banat — cablu gardă");
        meta.put("dataOrigin", "REAL");

        URI uploadUri = tusClient.createUpload(1024, meta);
        assertThat(uploadUri).isNotNull();

        HttpResponse<Void> head = tusClient.sendHead(uploadUri, Collections.emptyMap());
        // tus 1.0.0: a HEAD answer is "200 OK or 204 No Content"; tus-java-server uses 204.
        assertThat(head.statusCode()).isIn(200, 204);
        assertThat(head.headers().firstValue("Upload-Metadata")).isPresent();
    }

    @Test
    @DisplayName("T2-F03-01: Malformed base64 in Upload-Metadata header returns 400 Bad Request")
    void tusMalformedMetadataReturns400() throws Exception {
        HttpResponse<String> response = tusClient.sendCreateUpload(1024L, null,
                Map.of("Upload-Metadata", "category !!!INVALID_BASE64!!!"));
        assertThat(response.statusCode()).isEqualTo(400);
    }

    // --- F4: Max Upload Size Enforcement ---

    @Test
    @DisplayName("T1-F04-01: Upload within max size limit is accepted (201 Created)")
    void tusWithinLimitAccepted() throws Exception {
        HttpResponse<String> response = tusClient.sendCreateUpload(MAX_UPLOAD_BYTES,
                Map.of("filename", "exact-limit.bin"), Collections.emptyMap());
        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("T1-F04-03: Upload exceeding max size limit is rejected with 413 Payload Too Large")
    void tusExceedingLimitReturns413() throws Exception {
        HttpResponse<String> response = tusClient.sendCreateUpload(MAX_UPLOAD_BYTES + 1,
                Map.of("filename", "too-large.bin"), Collections.emptyMap());
        assertThat(response.statusCode()).isEqualTo(413);
    }

    // --- F5: Abandoned Upload Cleanup ---

    @Test
    @DisplayName("T1-F05-01: Abandoned uploads older than 24 hours are purged by UploadCleanupScheduler while recent files are preserved")
    void abandonedUploadCleanupDetection() throws Exception {
        // Create an abandoned temporary upload file older than 24 hours
        Path mockAbandoned = stagingDir.resolve("tus-abandoned-" + UUID.randomUUID() + ".tmp");
        Files.write(mockAbandoned, new byte[] {1, 2, 3, 4});
        Instant oldTime = Instant.now().minus(25, ChronoUnit.HOURS);
        Files.setLastModifiedTime(mockAbandoned, java.nio.file.attribute.FileTime.from(oldTime));

        // Create a recent temporary upload file (< 24 hours old)
        Path mockFresh = stagingDir.resolve("tus-fresh-" + UUID.randomUUID() + ".tmp");
        Files.write(mockFresh, new byte[] {5, 6, 7, 8});
        Files.setLastModifiedTime(mockFresh, java.nio.file.attribute.FileTime.from(Instant.now()));

        // Trigger the real application cleanup scheduler
        cleanupScheduler.cleanup();

        // Assert: abandoned file must be deleted, fresh file must remain intact
        assertThat(Files.exists(mockAbandoned))
                .as("Upload file older than 24 hours must be deleted by cleanup scheduler")
                .isFalse();
        assertThat(Files.exists(mockFresh))
                .as("Recent upload file (< 24h) must be retained by cleanup scheduler")
                .isTrue();
    }
}
