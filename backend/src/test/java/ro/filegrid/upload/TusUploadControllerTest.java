package ro.filegrid.upload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import me.desair.tus.server.TusFileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ro.filegrid.file.FileCategory;

import static org.assertj.core.api.Assertions.assertThat;

class TusUploadControllerTest {

    @TempDir
    Path tempDir;

    private TusFileUploadService tusFileUploadService;
    private StagingProperties stagingProperties;
    private TusMetadataParser metadataParser;
    private List<UploadCompletedEvent> emittedEvents;
    private TusUploadController controller;

    @BeforeEach
    void setUp() throws IOException {
        stagingProperties = new StagingProperties(tempDir.toString(), 5368709120L, Duration.ofHours(24), Duration.ofHours(1));
        Files.createDirectories(stagingProperties.tusDirectory());

        tusFileUploadService = new TusFileUploadService()
                .withStoragePath(stagingProperties.tusDirectory().toAbsolutePath().toString())
                .withUploadUri("/api/uploads")
                .withMaxUploadSize(stagingProperties.maxSizeBytes())
                .withUploadExpirationPeriod(stagingProperties.cleanupOlderThan().toMillis())
                .withThreadLocalCache(true);

        metadataParser = new TusMetadataParser();
        emittedEvents = new ArrayList<>();

        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof UploadCompletedEvent completedEvent) {
                emittedEvents.add(completedEvent);
            }
        };

        controller = new TusUploadController(tusFileUploadService, stagingProperties, metadataParser, eventPublisher);
    }

    private String encode(String val) {
        return Base64.getEncoder().encodeToString(val.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void optionsRequestReturnsTusHeaders() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/uploads");
        request.setRequestURI("/api/uploads");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.processTus(request, response);

        assertThat(response.getStatus()).isIn(200, 204);
        assertThat(response.getHeader("Tus-Resumable")).isEqualTo("1.0.0");
        assertThat(response.getHeader("Tus-Version")).contains("1.0.0");
    }

    @Test
    void postCreationHeadAndPatchUploadToCompletion() throws IOException {
        // 1. POST Creation
        MockHttpServletRequest postReq = new MockHttpServletRequest("POST", "/api/uploads");
        postReq.setRequestURI("/api/uploads");
        postReq.addHeader("Tus-Resumable", "1.0.0");
        postReq.addHeader("Upload-Length", "20");
        String metadataHeader = "category " + encode("METER_READING") + ",filename " + encode("sample.csv");
        postReq.addHeader("Upload-Metadata", metadataHeader);
        MockHttpServletResponse postResp = new MockHttpServletResponse();

        controller.processTus(postReq, postResp);

        assertThat(postResp.getStatus()).isEqualTo(201);
        String location = postResp.getHeader("Location");
        assertThat(location).isNotNull().startsWith("/api/uploads/");

        // 2. HEAD Request to check current offset
        MockHttpServletRequest headReq = new MockHttpServletRequest("HEAD", location);
        headReq.setRequestURI(location);
        headReq.addHeader("Tus-Resumable", "1.0.0");
        MockHttpServletResponse headResp = new MockHttpServletResponse();

        controller.processTus(headReq, headResp);

        assertThat(headResp.getStatus()).isIn(200, 204);
        assertThat(headResp.getHeader("Upload-Offset")).isEqualTo("0");
        assertThat(headResp.getHeader("Upload-Length")).isEqualTo("20");

        // 3. First PATCH (Chunk 1: 10 bytes)
        byte[] chunk1 = "0123456789".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest patch1Req = new MockHttpServletRequest("PATCH", location);
        patch1Req.setRequestURI(location);
        patch1Req.addHeader("Tus-Resumable", "1.0.0");
        patch1Req.addHeader("Upload-Offset", "0");
        patch1Req.setContentType("application/offset+octet-stream");
        patch1Req.setContent(chunk1);
        MockHttpServletResponse patch1Resp = new MockHttpServletResponse();

        controller.processTus(patch1Req, patch1Resp);

        assertThat(patch1Resp.getStatus()).isEqualTo(204);
        assertThat(patch1Resp.getHeader("Upload-Offset")).isEqualTo("10");
        assertThat(emittedEvents).isEmpty(); // Not complete yet

        // 4. Second PATCH (Chunk 2: 10 bytes, completing 20 bytes total)
        byte[] chunk2 = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest patch2Req = new MockHttpServletRequest("PATCH", location);
        patch2Req.setRequestURI(location);
        patch2Req.addHeader("Tus-Resumable", "1.0.0");
        patch2Req.addHeader("Upload-Offset", "10");
        patch2Req.setContentType("application/offset+octet-stream");
        patch2Req.setContent(chunk2);
        MockHttpServletResponse patch2Resp = new MockHttpServletResponse();

        controller.processTus(patch2Req, patch2Resp);

        assertThat(patch2Resp.getStatus()).isEqualTo(204);
        assertThat(patch2Resp.getHeader("Upload-Offset")).isEqualTo("20");

        // Verify completion event and promoted staging file
        assertThat(emittedEvents).hasSize(1);
        UploadCompletedEvent event = emittedEvents.get(0);
        assertThat(event.sizeBytes()).isEqualTo(20L);
        assertThat(event.originalFilename()).isEqualTo("sample.csv");
        assertThat(event.category()).isEqualTo(FileCategory.METER_READING);

        Path promotedFile = event.stagingPath();
        assertThat(Files.exists(promotedFile)).isTrue();
        assertThat(Files.size(promotedFile)).isEqualTo(20L);
        assertThat(Files.readAllBytes(promotedFile)).isEqualTo("0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
    }
}
