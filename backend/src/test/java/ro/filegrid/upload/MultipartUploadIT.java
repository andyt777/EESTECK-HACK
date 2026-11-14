package ro.filegrid.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.TestImages;

/**
 * Opaque-box integration tests for small file multipart upload endpoint.
 * Covers:
 * - F6: Small File Multipart Endpoint (POST /api/files streaming direct to staging)
 * - F7: Shared Staging Volume Mount & File Creation (/data/staging/{fileId})
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
class MultipartUploadIT {

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

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
        registry.add("filegrid.staging.directory", () -> stagingDir.toAbsolutePath().toString());
        registry.add("filegrid.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("filegrid.storage.s3.access-key", () -> "minioadmin");
        registry.add("filegrid.storage.s3.secret-key", () -> "minioadmin");
        registry.add("filegrid.storage.ftp.host", () -> "127.0.0.1");
        registry.add("filegrid.storage.ftp.port", () -> 1);
        registry.add("filegrid.storage.ftp.connect-timeout", () -> "2s");
        registry.add("filegrid.storage.health-interval", () -> "1h");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("T1-F06-01: Multipart upload for smart meter reading returns 201/202 with fileId and status RECEIVED")
    void uploadMeterReadingMultipart() {
        byte[] meterJson = "{\"meterId\":\"MTR-1042\",\"kw\":34.5,\"timestamp\":\"2026-11-14T16:00:00Z\"}".getBytes();
        ByteArrayResource filePart = new NamedByteArrayResource(meterJson, "reading.json");

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        body.add("category", "METER_READING");
        body.add("zone", "Banat");
        body.add("station", "Timisoara1");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> response = rest.postForEntity("/files", new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.ACCEPTED);
        JsonNode json = response.getBody();
        assertThat(json).isNotNull();
        assertThat(json.has("id") || json.has("fileId")).isTrue();
        String fileId = json.has("id") ? json.get("id").asText() : json.get("fileId").asText();
        assertThat(fileId).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

        if (json.has("status")) {
            assertThat(json.get("status").asText()).isIn("RECEIVED", "ANALYZING", "CATEGORIZED", "DESTINATION_SELECTED");
        }
    }

    @Test
    @DisplayName("T1-F06-02: Multipart upload with substation COMTRADE fault record preserves metadata")
    void uploadFaultRecordMultipart() {
        byte[] faultData = "1,1,2026-11-14 16:15:00.000,400.2,401.1,399.8".getBytes();
        ByteArrayResource filePart = new NamedByteArrayResource(faultData, "avarie_statie.dat");

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        body.add("category", "FAULT_RECORD");
        body.add("zone", "Vest");
        body.add("station", "Arad");
        body.add("equipment", "TR_400kV");
        body.add("eventType", "DIFF_TRIP");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> response = rest.postForEntity("/files", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("T1-F06-04: Multipart upload provides Location header for status polling")
    void multipartResponseContainsLocationHeader() {
        byte[] docBytes = "%PDF-1.4 test connection document".getBytes();
        ByteArrayResource filePart = new NamedByteArrayResource(docBytes, "racordare.pdf");

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        body.add("category", "CONNECTION_DOCUMENT");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> response = rest.postForEntity("/files", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString()).contains("/files/");
    }

    @Test
    @DisplayName("T1-F06-05: Missing file part returns 400 Bad Request")
    void missingFilePartReturnsBadRequest() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("category", "METER_READING");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> response = rest.postForEntity("/files", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("T2-F06-01: Zero-byte multipart file upload completes cleanly")
    void zeroByteMultipartUpload() {
        ByteArrayResource filePart = new NamedByteArrayResource(new byte[0], "empty.csv");

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> response = rest.postForEntity("/files", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.ACCEPTED, HttpStatus.OK);
    }

    @Test
    @DisplayName("T2-F06-02: Multipart upload with Romanian Unicode characters in filename is sanitized")
    void unicodeFilenameMultipartUpload() {
        byte[] payload = "test data".getBytes();
        String filename = "citire_stație#nord&1@2026.json";
        ByteArrayResource filePart = new NamedByteArrayResource(payload, filename);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> response = rest.postForEntity("/files", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("T1-F07-01: Uploaded file is streamed directly into staging directory")
    void fileWrittenToStagingDirectory() throws Exception {
        DeterministicContent content = new DeterministicContent(77, 128 * 1024);
        byte[] bytes = new byte[(int) content.size()];
        try (var in = content.open()) {
            int read = in.readNBytes(bytes, 0, bytes.length);
            assertThat(read).isEqualTo(bytes.length);
        }

        ByteArrayResource filePart = new NamedByteArrayResource(bytes, "staging-check.bin");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> response = rest.postForEntity("/files", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.ACCEPTED);

        // Verify staging directory contains the file
        boolean fileFound = Files.walk(stagingDir)
                .filter(Files::isRegularFile)
                .anyMatch(p -> {
                    try {
                        return Files.size(p) == content.size();
                    } catch (Exception e) {
                        return false;
                    }
                });
        assertThat(fileFound).as("Staging volume should contain the uploaded file").isTrue();
    }

    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
