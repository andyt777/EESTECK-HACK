package ro.filegrid.upload;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileSource;
import ro.filegrid.file.FileStatus;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;

/**
 * Controller handling multipart small/medium file uploads ({@code POST /api/files}).
 * Streams incoming bytes directly to the staging volume without buffering in heap,
 * computes SHA-256 on the fly, triggers classification, and records state
 * (all done by {@link FileIntakeService}).
 */
@RestController
@Profile("api")
public class MultipartFileController {

    private static final Logger log = LoggerFactory.getLogger(MultipartFileController.class);
    public static final String PERMISSION_WRITE = "file:write";

    private final FileIntakeService intake;
    private final CurrentActorProvider actorProvider;
    private final AccessPolicy accessPolicy;

    public MultipartFileController(FileIntakeService intake, CurrentActorProvider actorProvider,
                                   AccessPolicy accessPolicy) {
        this.intake = intake;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
    }

    public record FileUploadResponse(
            UUID id,
            String originalName,
            long sizeBytes,
            String sha256Plain,
            FileStatus status,
            FileCategory category,
            DataOrigin dataOrigin,
            Instant receivedAt
    ) {}

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "category", required = false) FileCategory category,
            @RequestParam(name = "zone", required = false) String zone,
            @RequestParam(name = "station", required = false) String station,
            @RequestParam(name = "equipment", required = false) String equipment,
            @RequestParam(name = "meterId", required = false) String meterId,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "eventTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant eventTime,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam(name = "dataOrigin", required = false, defaultValue = "REAL") DataOrigin dataOrigin,
            @RequestParam(name = "idempotencyKey", required = false) String idempotencyKey
    ) throws IOException {

        // 1. Authorization check
        String actor = actorProvider.currentActor();
        if (!accessPolicy.isAllowed(actor, PERMISSION_WRITE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Write permission denied for actor: " + actor);
        }

        // 2. Validate non-empty file
        // An empty file is a valid file (tus accepts it too); only a missing part is an error.
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is missing");
        }

        // 3. Idempotency check
        Optional<FileRecord> existing = intake.existing(idempotencyKey);
        if (existing.isPresent()) {
            return ResponseEntity.ok(toResponse(existing.get()));
        }

        // 4. Quick Content-Length upper-bound check
        long maxBytes = intake.maxSizeBytes();
        if (file.getSize() > 0 && file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File size exceeds limit of " + maxBytes + " bytes");
        }

        // 5. Stream to staging, classify, record, start ingestion
        FileRecord record;
        try (InputStream in = file.getInputStream()) {
            record = intake.accept(in, file.getOriginalFilename(), file.getContentType(), category,
                    new FileIntakeService.Context(zone, station, equipment, meterId, eventType, eventTime, description),
                    dataOrigin, idempotencyKey, FileSource.API, actor, "MULTIPART");
        } catch (FileIntakeService.TooLargeException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.error("Failed to stream multipart file to staging disk: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Disk write error", e);
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/files/" + record.getId())
                .body(toResponse(record));
    }

    private static FileUploadResponse toResponse(FileRecord r) {
        return new FileUploadResponse(
                r.getId(),
                r.getOriginalName(),
                r.getSizeBytes(),
                r.getSha256Plain(),
                r.getStatus(),
                r.getCategory(),
                r.getDataOrigin(),
                r.getReceivedAt()
        );
    }
}
