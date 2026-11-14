package ro.filegrid.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
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

    public TusUploadController(
            TusFileUploadService tusFileUploadService,
            StagingProperties stagingProperties,
            TusMetadataParser metadataParser,
            ApplicationEventPublisher eventPublisher) {
        this.tusFileUploadService = tusFileUploadService;
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

        String uri = request.getRequestURI();
        try {
            UploadInfo uploadInfo = tusFileUploadService.getUploadInfo(uri);
            if (uploadInfo != null && !uploadInfo.isUploadInProgress()) {
                handleUploadComplete(uploadInfo, uri);
            }
        } catch (TusException ex) {
            log.debug("No upload info available at uri '{}': {}", uri, ex.getMessage());
        }
    }

    private void handleUploadComplete(UploadInfo uploadInfo, String uploadUri) throws IOException {
        String uploadIdStr = uploadInfo.getId().toString();
        UUID fileId;
        try {
            fileId = UUID.fromString(uploadIdStr);
        } catch (IllegalArgumentException e) {
            fileId = UUID.randomUUID();
        }

        log.info("Tus upload completed: id={}, fileId={}, length={}",
                uploadIdStr, fileId, uploadInfo.getLength());

        Path stagingDir = stagingProperties.resolvedDirectory();
        Path targetStagingFile = stagingDir.resolve(fileId.toString());

        // Locate source chunk file on disk (in tus staging subfolder)
        Path tusDir = stagingProperties.tusDirectory();
        Path p1 = tusDir.resolve(uploadIdStr);
        Path p2 = tusDir.resolve(uploadIdStr + ".dat");
        Path sourceChunk = Files.isRegularFile(p1) ? p1 : (Files.isRegularFile(p2) ? p2 : null);

        if (sourceChunk != null) {
            try {
                Files.move(sourceChunk, targetStagingFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(sourceChunk, targetStagingFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            try (InputStream is = tusFileUploadService.getUploadedBytes(uploadUri)) {
                if (is != null) {
                    Files.copy(is, targetStagingFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (TusException e) {
                throw new IOException("Failed to read completed bytes from Tus service", e);
            }
        }

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

        // Clean up temporary tus metadata and lock files
        try {
            tusFileUploadService.deleteUpload(uploadUri);
        } catch (Exception e) {
            log.debug("Tus metadata cleanup for {} logged: {}", uploadUri, e.getMessage());
        }
    }
}
