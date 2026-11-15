package ro.filegrid.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.Sensitivity;
import ro.filegrid.storage.StorageProperties;
import ro.filegrid.storage.s3.S3ClientConfig;
import ro.filegrid.testsupport.TestImages;
import ro.filegrid.testsupport.TestTokens;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Stage 11 end to end (docs/AUTH-DESIGN.md): JWT validation (missing, expired, wrong audience,
 * wrong issuer), the role matrix on a personal-data document (field cannot read it and sees the
 * name masked, connections can), signed content links and {@code /me}; the actuator stays open on
 * the internal management port.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "worker"})
@Testcontainers
class AuthenticationIT {

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

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

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
        registry.add("management.server.port", () -> 0);
    }

    @BeforeAll
    static void initS3() {
        StorageProperties.S3 s3Props = new StorageProperties.S3(URI.create(S3.getS3URL()), "us-east-1",
                S3.getUserName(), S3.getPassword(), "filegrid-", "filegrid-benchmark", 16 * 1024 * 1024);
        S3Client s3 = S3ClientConfig.create(s3Props);
        for (String bucket : new String[] {"filegrid-benchmark", "filegrid-videos", "filegrid-documents",
                "filegrid-faults", "filegrid-meters"}) {
            s3.createBucket(b -> b.bucket(bucket));
        }
    }

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FileRecordRepository files;

    private URI api(String path) {
        return URI.create("http://localhost:" + port + "/api" + path);
    }

    private HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(api(path));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String json)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(api(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertUnauthorizedProblem(HttpResponse<String> response) throws IOException {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("")).contains("problem+json");
        assertThat(JSON.readTree(response.body()).path("status").asInt()).isEqualTo(401);
    }

    @Test
    @DisplayName("missing, malformed, expired, wrong-audience and wrong-issuer tokens get 401 problem details")
    void invalidTokensAreRejected() throws Exception {
        assertUnauthorizedProblem(get("/files", null));
        assertUnauthorizedProblem(get("/files", "not-a-jwt"));
        assertUnauthorizedProblem(get("/files", TestTokens.sign("field.demo", Instant.now().minusSeconds(600),
                TestTokens.ISSUER, "filegrid-api", "field")));
        assertUnauthorizedProblem(get("/files", TestTokens.sign("field.demo", Instant.now().plusSeconds(300),
                TestTokens.ISSUER, "another-api", "field")));
        assertUnauthorizedProblem(get("/files", TestTokens.sign("field.demo", Instant.now().plusSeconds(300),
                "https://evil.example/realms/filegrid", "filegrid-api", "field")));
        assertThat(get("/files", TestTokens.token("field.demo", "field")).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("/me returns the actor, roles and permissions of the token")
    void meDescribesTheCaller() throws Exception {
        HttpResponse<String> response = get("/me", TestTokens.token("connections.demo", "connections"));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode me = JSON.readTree(response.body());
        assertThat(me.path("actor").asText()).isEqualTo("connections.demo");
        assertThat(me.path("roles").toString()).contains("connections");
        assertThat(me.path("permissions").toString()).contains(RolePermissions.PII_READ);
        assertThat(me.path("expiresAt").isNull() || me.path("expiresAt").isMissingNode()).isFalse();

        JsonNode field = JSON.readTree(get("/me", TestTokens.token("field.demo", "field")).body());
        assertThat(field.path("permissions").toString()).doesNotContain(RolePermissions.PII_READ);
    }

    @Test
    @DisplayName("actuator health is open on the internal management port without a token")
    void actuatorIsOpenOnManagementPort() throws Exception {
        HttpResponse<String> health = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("personal data: field gets 403 and a masked name, connections reads it, signed link works without a header")
    void personalDataFollowsTheRoleMatrix() throws Exception {
        String name = "cerere-racordare-ion-popescu.pdf";
        byte[] pdf = pdf(name, 4096);
        UUID id = uploadAndAwaitStored(pdf, name);
        assertThat(files.findById(id).orElseThrow().getSensitivity()).isEqualTo(Sensitivity.PERSONAL_DATA);

        String field = TestTokens.token("field.demo", "field");
        String connections = TestTokens.token("connections.demo", "connections");

        assertThat(get("/files/" + id + "/content", field).statusCode()).isEqualTo(403);
        assertThat(post("/files/" + id + "/content-link", field, "{}").statusCode()).isEqualTo(403);
        assertThat(get("/files?category=CONNECTION_DOCUMENT", field).body())
                .contains(id.toString()).doesNotContain(name);
        assertThat(get("/files/" + id + "/passport", field).body()).doesNotContain(name);

        assertThat(get("/files?category=CONNECTION_DOCUMENT", connections).body()).contains(name);
        HttpResponse<byte[]> direct = HTTP.send(HttpRequest.newBuilder(api("/files/" + id + "/content"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + connections).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(direct.statusCode()).isEqualTo(200);
        assertThat(direct.body()).isEqualTo(pdf);

        HttpResponse<String> link = post("/files/" + id + "/content-link", connections,
                "{\"disposition\":\"attachment\"}");
        assertThat(link.statusCode()).isEqualTo(200);
        String url = JSON.readTree(link.body()).path("url").asText();
        assertThat(url).startsWith("/api/files/" + id + "/content?");

        HttpResponse<byte[]> viaLink = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(viaLink.statusCode()).isEqualTo(200);
        assertThat(viaLink.body()).isEqualTo(pdf);

        HttpResponse<String> tampered = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + url.replace("act=connections.demo", "act=admin.demo"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tampered.statusCode()).isEqualTo(401);
    }

    private UUID uploadAndAwaitStored(byte[] content, String name) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_PDF);
        body.add("file", new HttpEntity<>(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return name;
            }
        }, partHeaders));
        body.add("category", "CONNECTION_DOCUMENT");
        body.add("station", "Statia Test Auth");
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
}
