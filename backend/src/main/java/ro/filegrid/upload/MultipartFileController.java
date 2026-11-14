package ro.filegrid.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
import ro.filegrid.file.FileSource;
import ro.filegrid.file.FileStatus;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.storage.Sha256;

/**
 * Controller handling multipart small/medium file uploads ({@code POST /api/files}).
 * Streams incoming bytes directly to the staging volume without buffering in heap,
 * computes SHA-256 on the fly, triggers classification, and records state.
 */
@RestController
@Profile("api")
public class MultipartFileController {

    private static final Logger log = LoggerFactory.getLogger(MultipartFileController.class);
    public static final String PERMISSION_WRITE = "file:write";
    private static final int BUFFER_SIZE = 65536; // 64 KB streaming buffer

    private final StagingProperties stagingProperties;
    private final FileRecordRepository fileRecordRepository;
    private final FileContextRepository fileContextRepository;
    private final FileEventRepository fileEventRepository;
    private final ClassificationService classificationService;
    private final CurrentActorProvider actorProvider;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public MultipartFileController(
            StagingProperties stagingProperties,
            FileRecordRepository fileRecordRepository,
            FileContextRepository fileContextRepository,
            FileEventRepository fileEventRepository,
            ClassificationService classificationService,
            CurrentActorProvider actorProvider,
            AccessPolicy accessPolicy,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.stagingProperties = stagingProperties;
        this.fileRecordRepository = fileRecordRepository;
        this.fileContextRepository = fileContextRepository;
        this.fileEventRepository = fileEventRepository;
        this.classificationService = classificationService;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
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
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<FileRecord> existing = fileRecordRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                FileRecord r = existing.get();
                return ResponseEntity.ok(toResponse(r));
            }
        }

        // 4. Quick Content-Length upper-bound check
        long maxBytes = stagingProperties.maxSizeBytes();
        if (file.getSize() > 0 && file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File size exceeds limit of " + maxBytes + " bytes");
        }

        // 5. Initialize staging destination paths
        UUID fileId = UUID.randomUUID();
        Path stagingDir = stagingProperties.resolvedDirectory();
        Path tmpPath = stagingDir.resolve(fileId + ".tmp");
        Path finalPath = stagingDir.resolve(fileId.toString());

        MessageDigest digest = Sha256.newDigest();
        byte[] head = new byte[ClassificationRequest.HEAD_BYTES];
        int headOffset = 0;
        long totalBytesWritten = 0;

        // 6. Stream directly to disk with single-pass SHA-256 calculation
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = file.getInputStream();
             OutputStream out = Files.newOutputStream(tmpPath,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {

            int read;
            while ((read = in.read(buffer)) != -1) {
                totalBytesWritten += read;
                if (totalBytesWritten > maxBytes) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "File size exceeds maximum allowed size of " + maxBytes + " bytes");
                }

                // Capture up to 64KB magic bytes for classification
                if (headOffset < ClassificationRequest.HEAD_BYTES) {
                    int toCopy = Math.min(read, ClassificationRequest.HEAD_BYTES - headOffset);
                    System.arraycopy(buffer, 0, head, headOffset, toCopy);
                    headOffset += toCopy;
                }

                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (ResponseStatusException e) {
            Files.deleteIfExists(tmpPath);
            throw e;
        } catch (Exception e) {
            Files.deleteIfExists(tmpPath);
            log.error("Failed to stream multipart file to staging disk: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Disk write error", e);
        }

        // 7. Atomic move from .tmp to final staging path
        try {
            Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String sha256Hex = Sha256.hex(digest);
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin";
        Instant now = Instant.now();

        // 8. Trim head buffer if less than 64KB read
        byte[] actualHead = headOffset == head.length ? head : java.util.Arrays.copyOf(head, headOffset);

        // 9. Run Classification to resolve category
        Map<String, String> metaMap = buildMetadataMap(zone, station, equipment, meterId, eventType, eventTime, description);
        ClassificationRequest classReq = new ClassificationRequest(category, originalName, file.getContentType(), actualHead, metaMap);
        ClassificationResult classResult = classificationService.classify(classReq);
        FileCategory resolvedCategory = classResult.category() != null ? classResult.category() : FileCategory.CONNECTION_DOCUMENT;

        // 10. Persist FileRecord
        FileRecord record = new FileRecord(fileId, resolvedCategory, originalName, totalBytesWritten,
                dataOrigin != null ? dataOrigin : DataOrigin.REAL, FileSource.API, actor, now);
        record.setContentType(file.getContentType());
        record.setSha256Plain(sha256Hex);
        record.setIdempotencyKey(idempotencyKey);
        record.setTraceId(ro.filegrid.observability.Tracing.currentTraceIdOrRandom());
        fileRecordRepository.save(record);

        // 11. Persist FileContext
        FileContext context = new FileContext(fileId);
        context.setZone(zone);
        context.setStation(station);
        context.setEquipment(equipment);
        context.setMeterId(meterId);
        context.setEventType(eventType);
        context.setEventTime(eventTime);
        context.setDescription(description);
        fileContextRepository.save(context);

        // 12. Record append-only FileEvent
        ObjectNode eventDetails = objectMapper.createObjectNode()
                .put("originalName", originalName)
                .put("sizeBytes", totalBytesWritten)
                .put("sha256Plain", sha256Hex)
                .put("source", "MULTIPART");
        FileEvent fileEvent = new FileEvent(fileId, FileEventType.RECEIVED, actor, eventDetails, record.getTraceId(), now);
        fileEventRepository.save(fileEvent);

        // 13. Publish UploadCompletedEvent (triggers Milestone 2 Ingestion pipeline)
        eventPublisher.publishEvent(new UploadCompletedEvent(
                fileId, finalPath, totalBytesWritten, sha256Hex, resolvedCategory, record, now));

        log.info("Multipart upload complete: fileId={}, size={}, sha256={}, category={}",
                fileId, totalBytesWritten, sha256Hex, resolvedCategory);

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/files/" + fileId)
                .body(toResponse(record));
    }

    private static Map<String, String> buildMetadataMap(
            String zone, String station, String equipment, String meterId,
            String eventType, Instant eventTime, String description
    ) {
        Map<String, String> meta = new HashMap<>();
        if (zone != null) meta.put("zone", zone);
        if (station != null) meta.put("station", station);
        if (equipment != null) meta.put("equipment", equipment);
        if (meterId != null) meta.put("meterId", meterId);
        if (eventType != null) meta.put("eventType", eventType);
        if (eventTime != null) meta.put("eventTime", eventTime.toString());
        if (description != null) meta.put("description", description);
        return meta;
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
