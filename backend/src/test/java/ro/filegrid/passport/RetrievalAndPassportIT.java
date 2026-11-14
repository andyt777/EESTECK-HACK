package ro.filegrid.passport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.storage.StorageProperties;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.database.StoreDbConfig;
import ro.filegrid.storage.s3.S3ClientConfig;
import ro.filegrid.testsupport.TestImages;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Retrieval with integrity checking, File Passport and search, end to end:
 * upload, store, read back, then corrupt the object directly in the storage
 * (S3 and Database) and check that FileGrid refuses to serve it as valid.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "worker"})
@Testcontainers
class RetrievalAndPassportIT {

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
        registry.add("filegrid.storage.health-interval", () -> "2s");
        registry.add("filegrid.policy.file", () -> "src/test/resources/policy/fast-retry-policies.yaml");
        registry.add("filegrid.worker.categories",
                () -> "METER_READING,FAULT_RECORD,CONNECTION_DOCUMENT,DRONE_VIDEO");
    }

    @BeforeAll
    static void initS3() {
        StorageProperties.S3 s3Props = new StorageProperties.S3(URI.create(S3.getS3URL()), "us-east-1",
                S3.getUserName(), S3.getPassword(), "filegrid-", "filegrid-benchmark", 16 * 1024 * 1024);
        s3Client = S3ClientConfig.create(s3Props);
        for (String bucket : new String[] {"filegrid-benchmark", "filegrid-videos", "filegrid-documents",
                "filegrid-faults", "filegrid-meters"}) {
            s3Client.createBucket(b -> b.bucket(bucket));
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FileRecordRepository files;

    @Autowired
    @Qualifier(StoreDbConfig.QUALIFIER)
    private JdbcTemplate storeDb;

    @Test
    @DisplayName("stored file: passport, verified download, range, search and category summary")
    void passportDownloadAndSearch() {
        byte[] original = pdf("racordare demo", 200_000);
        UUID id = uploadAndAwaitStored(original, "cerere-racordare-demo.pdf", "application/pdf",
                "CONNECTION_DOCUMENT", "Statia Test Passport");

        JsonNode passport = rest.getForObject("/files/{id}/passport", JsonNode.class, id);
        assertThat(passport.path("status").asText()).isEqualTo("STORED_VERIFIED");
        assertThat(passport.path("identity").path("name").asText()).isEqualTo("cerere-racordare-demo.pdf");
        assertThat(passport.path("context").path("station").asText()).isEqualTo("Statia Test Passport");
        assertThat(passport.path("context").has("meterId")).as("empty context fields are not sent").isFalse();
        assertThat(passport.path("journey")).hasSize(7);
        passport.path("journey").forEach(step -> assertThat(step.path("done").asBoolean()).isTrue());
        assertThat(passport.path("integrity").path("status").asText()).isEqualTo("VERIFIED_INTACT");
        assertThat(passport.path("decision").path("explanation").asText()).isNotBlank();
        assertThat(texts(passport.path("actions"))).contains("DOWNLOAD", "VERIFY", "PREVIEW");
        assertThat(types(passport)).contains("RECEIVED", "ROUTED", "STORED", "VERIFIED", "ACCESSED");

        ResponseEntity<byte[]> download = rest.getForEntity("/files/{id}/content", byte[].class, id);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody()).isEqualTo(original);
        assertThat(download.getHeaders().getFirst("X-Integrity")).isEqualTo("verified");
        assertThat(download.getHeaders().getFirst("X-Content-SHA256"))
                .isEqualTo(passport.path("integrity").path("sha256").asText());

        HttpHeaders range = new HttpHeaders();
        range.set(HttpHeaders.RANGE, "bytes=10-19");
        ResponseEntity<byte[]> part = rest.exchange("/files/{id}/content?disposition=inline", HttpMethod.GET,
                new HttpEntity<>(range), byte[].class, id);
        assertThat(part.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(part.getBody()).isEqualTo(java.util.Arrays.copyOfRange(original, 10, 20));
        assertThat(part.getHeaders().getFirst("X-Integrity")).isEqualTo("partial");

        assertThat(types(rest.getForObject("/files/{id}/passport", JsonNode.class, id))).contains("DOWNLOADED");

        JsonNode found = rest.getForObject("/files?q=Test Passport&category=CONNECTION_DOCUMENT", JsonNode.class);
        assertThat(found.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(found.path("items").get(0).path("id").asText()).isEqualTo(id.toString());
        assertThat(rest.getForObject("/files?q=" + id, JsonNode.class).path("total").asLong()).isEqualTo(1);
        assertThat(rest.getForObject("/files?q=nu-exista-asa-ceva", JsonNode.class).path("total").asLong()).isZero();

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode summary = rest.getForObject("/categories/summary", JsonNode.class);
            assertThat(summary).hasSize(4);
            JsonNode documents = null;
            for (JsonNode category : summary) {
                if ("CONNECTION_DOCUMENT".equals(category.path("category").asText())) {
                    documents = category;
                }
            }
            assertThat(documents).isNotNull();
            assertThat(documents.path("fileCount").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(documents.path("recent")).isNotEmpty();
        });

        assertThat(rest.getForEntity("/files/{id}/passport", JsonNode.class, UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("object corrupted directly in S3: download refused with 409, passport shows the mismatch")
    void corruptionInS3IsDetectedAtRetrieval() {
        byte[] original = pdf("document s3", 120_000);
        UUID id = uploadAndAwaitStored(original, "document-s3.pdf", "application/pdf", "CONNECTION_DOCUMENT",
                "Statia S3");
        FileRecord file = files.findById(id).orElseThrow();
        assertThat(file.getStorageType()).isEqualTo(StorageType.S3);

        byte[] corrupted = original.clone();
        corrupted[0] ^= (byte) 0xFF;
        String key = file.getStorageKey();
        String bucket = "filegrid-" + key.substring(0, key.indexOf('/'));
        s3Client.putObject(b -> b.bucket(bucket).key(key.substring(key.indexOf('/') + 1)),
                RequestBody.fromBytes(corrupted));

        ResponseEntity<JsonNode> refused = rest.getForEntity("/files/{id}/content", JsonNode.class, id);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().path("code").asText()).isEqualTo("integrity-mismatch");

        JsonNode passport = rest.getForObject("/files/{id}/passport", JsonNode.class, id);
        assertThat(passport.path("integrity").path("status").asText()).isEqualTo("MISMATCH");
        assertThat(types(passport)).contains("INTEGRITY_INCIDENT");
        assertThat(texts(passport.path("actions"))).doesNotContain("DOWNLOAD", "PREVIEW");

        // Still refused on the next attempt, and served only when the suspicion is acknowledged.
        assertThat(rest.getForEntity("/files/{id}/content", JsonNode.class, id).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        // A connection document is encrypted: an altered ciphertext cannot be decrypted, so even an
        // acknowledged suspect download is refused (there is no content to serve).
        assertThat(file.isEncrypted()).isTrue();
        ResponseEntity<JsonNode> acknowledged = rest.getForEntity("/files/{id}/content?acknowledgeSuspect=true",
                JsonNode.class, id);
        assertThat(acknowledged.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode suspects = rest.getForObject("/files?integrity=MISMATCH", JsonNode.class);
        assertThat(suspects.path("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("object corrupted directly in the Database storage: on-demand verification reports the mismatch")
    void corruptionInDatabaseIsDetectedByVerify() {
        byte[] reading = "{\"meterId\":\"MTR-TEST-1\",\"values\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
        UUID id = uploadAndAwaitStored(reading, "reading-test.json", "application/json", "METER_READING",
                "Statia DB");
        FileRecord file = files.findById(id).orElseThrow();
        assertThat(file.getStorageType()).isEqualTo(StorageType.DATABASE);

        JsonNode intact = rest.postForObject("/files/{id}/verify", null, JsonNode.class, id);
        assertThat(intact.path("intact").asBoolean()).isTrue();

        int updated = storeDb.update(
                "UPDATE stored_blob SET content = set_byte(content, 0, get_byte(content, 0) # 255) WHERE key = ?",
                file.getStorageKey());
        assertThat(updated).isEqualTo(1);

        JsonNode mismatch = rest.postForObject("/files/{id}/verify", null, JsonNode.class, id);
        assertThat(mismatch.path("intact").asBoolean()).isFalse();
        assertThat(mismatch.path("integrityStatus").asText()).isEqualTo("MISMATCH");
        assertThat(rest.getForEntity("/files/{id}/content", JsonNode.class, id).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private UUID uploadAndAwaitStored(byte[] content, String name, String contentType, String category,
                                      String station) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        body.add("file", new HttpEntity<>(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return name;
            }
        }, partHeaders));
        body.add("category", category);
        body.add("station", station);
        body.add("dataOrigin", "SIMULATED");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> response = rest.postForEntity("/files", new HttpEntity<>(body, headers),
                JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        UUID id = UUID.fromString(response.getBody().path("id").asText());
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(files.findById(id).orElseThrow().getStatus().name()).isEqualTo("STORED_VERIFIED"));
        return id;
    }

    private static byte[] pdf(String label, int size) {
        byte[] bytes = new byte[size];
        new java.util.Random(label.hashCode()).nextBytes(bytes);
        byte[] header = ("%PDF-1.4\n% " + label + "\n").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    private static List<String> types(JsonNode passport) {
        List<String> types = new ArrayList<>();
        passport.path("audit").path("items").forEach(e -> types.add(e.path("type").asText()));
        return types;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }
}
