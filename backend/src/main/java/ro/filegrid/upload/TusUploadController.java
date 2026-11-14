package ro.filegrid.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import ro.filegrid.file.FileRecordRepository;

/**
 * Resumable upload controller handling Tus 1.0 protocol methods ({@code POST}, {@code PATCH},
 * {@code HEAD}, {@code OPTIONS}, {@code DELETE}) under {@code /uploads} and {@code /uploads/**}
 * (exposed externally as {@code /api/uploads}).
 */
@RestController
@Profile("api")
public class TusUploadController {

    private static final Logger log = LoggerFactory.getLogger(TusUploadController.class);

    private final TusFileUploadService tusFileUploadService;
    private final StagingProperties stagingProperties;
    private final TusMetadataParser metadataParser;
    private final ApplicationEventPublisher eventPublisher;
    private final FileRecordRepository files;

    public TusUploadController(
            TusFileUploadService tusFileUploadService,
            StagingProperties stagingProperties,
            TusMetadataParser metadataParser,
            ApplicationEventPublisher eventPublisher,
            FileRecordRepository files) {
        this.tusFileUploadService = tusFileUploadService;
        this.files = files;
        this.stagingProperties = stagingProperties;
        this.metadataParser = metadataParser;
        this.eventPublisher = eventPublisher;
    }

    @RequestMapping(
            value = {"/uploads", "/uploads/**"},
            method = {
                    RequestMethod.POST,
                    RequestMethod.PATCH,
                    RequestMethod.HEAD,
                    RequestMethod.OPTIONS,
                    RequestMethod.DELETE
            }
    )
    public void processTus(HttpServletRequest request, HttpServletResponse response) throws IOException {
        tusFileUploadService.process(request, response);

        // Only the request that wrote the last bytes completes an upload: a PATCH, or a
        // POST creating an empty upload. HEAD and OPTIONS never trigger ingestion again.
        String method = request.getMethod();
        String uri;
        if ("PATCH".equalsIgnoreCase(method)) {
            uri = request.getRequestURI();
        } else if ("POST".equalsIgnoreCase(method) && response.getStatus() == HttpServletResponse.SC_CREATED
                && response.getHeader("Location") != null) {
            uri = java.net.URI.create(response.getHeader("Location")).getPath();
        } else {
            return;
        }
        try {
            UploadInfo uploadInfo = tusFileUploadService.getUploadInfo(uri);
            if (uploadInfo != null && uploadInfo.getLength() != null && !uploadInfo.isUploadInProgress()) {
                handleUploadComplete(uploadInfo, uri);
            }
        } catch (TusException ex) {
            log.debug("No upload info available at uri '{}': {}", uri, ex.getMessage());
        }
    }

    /**
     * Moves the completed bytes into staging (a rename, never a copy) and hands
     * the file to ingestion. The tus upload record itself is kept until it
     * expires, so that a client asking HEAD after completion still gets
     * {@code Upload-Offset == Upload-Length} as the protocol requires.
     */
    private void handleUploadComplete(UploadInfo uploadInfo, String uploadUri) throws IOException {
        String uploadIdStr = uploadInfo.getId().toString();
        UUID fileId;
        try {
            fileId = UUID.fromString(uploadIdStr);
        } catch (IllegalArgumentException e) {
            fileId = UUID.nameUUIDFromBytes(uploadIdStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        if (files.existsById(fileId)) {
            log.debug("Tus upload {} was already handed to ingestion", uploadIdStr);
            return;
        }
        Path stagingDir = stagingProperties.resolvedDirectory();
        Path targetStagingFile = stagingDir.resolve(fileId.toString());

        // tus DiskStorageService layout: <storagePath>/uploads/<id>/data
        Path sourceChunk = stagingProperties.tusDirectory().resolve("uploads").resolve(uploadIdStr).resolve("data");
        if (!Files.isRegularFile(sourceChunk)) {
            if (Files.exists(targetStagingFile)) {
                log.debug("Tus upload {} was already handed to ingestion", uploadIdStr);
                return;
            }
            if (uploadInfo.getLength() != 0L) {
                log.warn("Completed tus upload {} has no data file at {}", uploadIdStr, sourceChunk);
                return;
            }
            Files.createFile(targetStagingFile);
        } else {
            try {
                Files.move(sourceChunk, targetStagingFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(sourceChunk, targetStagingFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.info("Tus upload completed: id={}, fileId={}, length={}", uploadIdStr, fileId, uploadInfo.getLength());

        long size = Files.exists(targetStagingFile) ? Files.size(targetStagingFile)
                : (uploadInfo.getLength() != null ? uploadInfo.getLength() : 0L);

        // Parse Tus metadata header
        TusMetadataParser.ParsedTusMetadata parsed = metadataParser.parse(uploadInfo.getEncodedMetadata());

        String originalFilename = parsed.filename()
                .or(() -> java.util.Optional.ofNullable(uploadInfo.getFileName()))
                .orElse("upload-" + fileId);
        String contentType = parsed.contentType()
                .or(() -> java.util.Optional.ofNullable(uploadInfo.getFileMimeType()))
                .orElse("application/octet-stream");

        // Publish event for IngestionService pickup (Milestone 2)
        UploadCompletedEvent event = new UploadCompletedEvent(
                fileId,
                targetStagingFile,
                size,
                originalFilename,
                contentType,
                parsed.category().orElse(null),
                parsed.toContextMap()
        );
        eventPublisher.publishEvent(event);

    }
}
