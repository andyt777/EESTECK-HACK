package ro.filegrid.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ro.filegrid.classification.ClassificationRequest;
import ro.filegrid.classification.ClassificationResult;
import ro.filegrid.classification.ClassificationService;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileContext;
import ro.filegrid.file.FileContextRepository;
import ro.filegrid.file.FileEvent;
import ro.filegrid.file.FileEventRepository;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileStatus;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.storage.Sha256;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultipartFileControllerTest {

    @TempDir
    Path tempDir;

    private StagingProperties stagingProperties;
    private FileRecordRepository fileRecordRepository;
    private FileContextRepository fileContextRepository;
    private FileEventRepository fileEventRepository;
    private ClassificationService classificationService;
    private CurrentActorProvider actorProvider;
    private AccessPolicy accessPolicy;
    private List<UploadCompletedEvent> emittedEvents;
    private MultipartFileController controller;

    @BeforeEach
    void setUp() {
        stagingProperties = new StagingProperties(tempDir.toString(), 1024 * 1024L, Duration.ofHours(24), Duration.ofHours(1));
        fileRecordRepository = mock(FileRecordRepository.class);
        fileContextRepository = mock(FileContextRepository.class);
        fileEventRepository = mock(FileEventRepository.class);
        classificationService = mock(ClassificationService.class);
        actorProvider = mock(CurrentActorProvider.class);
        accessPolicy = mock(AccessPolicy.class);
        emittedEvents = new ArrayList<>();

        when(actorProvider.currentActor()).thenReturn("operator-user");
        when(accessPolicy.isAllowed("operator-user", "file:write")).thenReturn(true);

        when(classificationService.classify(any(ClassificationRequest.class)))
                .thenReturn(new ClassificationResult(
                        FileCategory.METER_READING,
                        ClassificationResult.Basis.DECLARED,
                        FileCategory.METER_READING,
                        FileCategory.METER_READING,
                        "text/csv",
                        "text/csv",
                        "csv",
                        List.of(),
                        List.of()
                ));

        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof UploadCompletedEvent uce) {
                emittedEvents.add(uce);
            }
        };

        controller = new MultipartFileController(
                stagingProperties,
                fileRecordRepository,
                fileContextRepository,
                fileEventRepository,
                classificationService,
                actorProvider,
                accessPolicy,
                eventPublisher,
                new ObjectMapper()
        );
    }

    @Test
    void successfulUploadStreamsToDiskPersistsMetadataAndPublishesEvent() throws IOException {
        byte[] content = "meter-id,timestamp,voltage\n1,2026-11-14,230".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "meter.csv", "text/csv", content);

        ResponseEntity<MultipartFileController.FileUploadResponse> response = controller.uploadFile(
                file,
                FileCategory.METER_READING,
                "Muntenia",
                "Statia Nord",
                "TR1",
                "MTR-1",
                "SURGE",
                Instant.parse("2026-11-14T12:00:00Z"),
                "High surge observed",
                DataOrigin.REAL,
                null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MultipartFileController.FileUploadResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.originalName()).isEqualTo("meter.csv");
        assertThat(body.sizeBytes()).isEqualTo((long) content.length);
        assertThat(body.category()).isEqualTo(FileCategory.METER_READING);
        assertThat(body.status()).isEqualTo(FileStatus.RECEIVED);

        // Verify file stored on staging volume
        Path stagedFile = stagingProperties.resolvedDirectory().resolve(body.id().toString());
        assertThat(Files.exists(stagedFile)).isTrue();
        assertThat(Files.readAllBytes(stagedFile)).isEqualTo(content);

        // Verify entities saved
        ArgumentCaptor<FileRecord> recordCaptor = ArgumentCaptor.forClass(FileRecord.class);
        verify(fileRecordRepository).save(recordCaptor.capture());
        FileRecord savedRecord = recordCaptor.getValue();
        assertThat(savedRecord.getStatus()).isEqualTo(FileStatus.RECEIVED);
        assertThat(savedRecord.getUploadedBy()).isEqualTo("operator-user");
        assertThat(savedRecord.getSizeBytes()).isEqualTo(content.length);

        ArgumentCaptor<FileContext> contextCaptor = ArgumentCaptor.forClass(FileContext.class);
        verify(fileContextRepository).save(contextCaptor.capture());
        FileContext savedContext = contextCaptor.getValue();
        assertThat(savedContext.getZone()).isEqualTo("Muntenia");
        assertThat(savedContext.getMeterId()).isEqualTo("MTR-1");

        ArgumentCaptor<FileEvent> eventCaptor = ArgumentCaptor.forClass(FileEvent.class);
        verify(fileEventRepository).save(eventCaptor.capture());
        FileEvent savedEvent = eventCaptor.getValue();
        assertThat(savedEvent.getType()).isEqualTo(FileEventType.RECEIVED);
        assertThat(savedEvent.getActor()).isEqualTo("operator-user");

        // Verify event publication
        assertThat(emittedEvents).hasSize(1);
        UploadCompletedEvent uce = emittedEvents.get(0);
        assertThat(uce.fileId()).isEqualTo(body.id());
        assertThat(uce.sizeBytes()).isEqualTo((long) content.length);
    }

    @Test
    void rejectsOversizedUpload() {
        StagingProperties strictProps = new StagingProperties(tempDir.toString(), 20L, Duration.ofHours(24), Duration.ofHours(1));
        MultipartFileController strictController = new MultipartFileController(
                strictProps,
                fileRecordRepository,
                fileContextRepository,
                fileEventRepository,
                classificationService,
                actorProvider,
                accessPolicy,
                mock(ApplicationEventPublisher.class),
                new ObjectMapper()
        );

        byte[] largeContent = new byte[100];
        MockMultipartFile file = new MockMultipartFile("file", "oversized.bin", "application/octet-stream", largeContent);

        assertThatThrownBy(() -> strictController.uploadFile(
                file, null, null, null, null, null, null, null, null, DataOrigin.REAL, null
        )).isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void rejectsUnauthorizedActor() {
        when(accessPolicy.isAllowed("unauthorized-user", "file:write")).thenReturn(false);
        when(actorProvider.currentActor()).thenReturn("unauthorized-user");

        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", content);

        assertThatThrownBy(() -> controller.uploadFile(
                file, null, null, null, null, null, null, null, null, DataOrigin.REAL, null
        )).isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN);

        verify(fileRecordRepository, never()).save(any());
    }

    @Test
    void returnsExistingRecordWhenIdempotencyKeyMatches() throws IOException {
        String key = "idempotency-key-abc";
        UUID existingId = UUID.randomUUID();
        FileRecord existingRecord = new FileRecord(existingId, FileCategory.FAULT_RECORD, "fault.dat", 500L,
                DataOrigin.REAL, ro.filegrid.file.FileSource.API, "operator-user", Instant.now());
        existingRecord.setIdempotencyKey(key);

        when(fileRecordRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existingRecord));

        byte[] content = "new-content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "fault.dat", "application/octet-stream", content);

        ResponseEntity<MultipartFileController.FileUploadResponse> response = controller.uploadFile(
                file, null, null, null, null, null, null, null, null, DataOrigin.REAL, key
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(existingId);
        assertThat(response.getBody().category()).isEqualTo(FileCategory.FAULT_RECORD);

        // Verifies no second record saved
        verify(fileRecordRepository, never()).save(any());
        verify(fileContextRepository, never()).save(any());
    }
}
