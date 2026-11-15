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
import org.springframework.stereotype.Service;
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
import ro.filegrid.storage.Sha256;

/**
 * Accepts one file from a stream (multipart upload, WhatsApp photo): streams it to the staging volume with
 * a 64 KB buffer while computing SHA-256, classifies it, records it and starts the ingestion pipeline.
 * The whole content is never held in memory.
 */
@Service
@Profile("api")
public class FileIntakeService {

    private static final Logger log = LoggerFactory.getLogger(FileIntakeService.class);
    private static final int BUFFER_SIZE = 65536;

    /** Operational context of the file (all optional). */
    public record Context(String zone, String station, String equipment, String meterId, String eventType,
                          Instant eventTime, String description) {
        public static Context empty() {
            return new Context(null, null, null, null, null, null, null);
        }

        Map<String, String> asMap() {
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
    }

    /** The stream was longer than the staging limit; nothing was kept. */
    public static class TooLargeException extends IOException {
        public TooLargeException(long maxBytes) {
            super("File size exceeds maximum allowed size of " + maxBytes + " bytes");
        }
    }

    private final StagingProperties stagingProperties;
    private final FileRecordRepository fileRecordRepository;
    private final FileContextRepository fileContextRepository;
    private final FileEventRepository fileEventRepository;
    private final ClassificationService classificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public FileIntakeService(StagingProperties stagingProperties, FileRecordRepository fileRecordRepository,
                             FileContextRepository fileContextRepository, FileEventRepository fileEventRepository,
                             ClassificationService classificationService, ApplicationEventPublisher eventPublisher,
                             ObjectMapper objectMapper) {
        this.stagingProperties = stagingProperties;
        this.fileRecordRepository = fileRecordRepository;
        this.fileContextRepository = fileContextRepository;
        this.fileEventRepository = fileEventRepository;
        this.classificationService = classificationService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    public long maxSizeBytes() {
        return stagingProperties.maxSizeBytes();
    }

    /** An earlier file with the same idempotency key, if any. */
    public Optional<FileRecord> existing(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return fileRecordRepository.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * Streams {@code in} into staging and records the file. {@code sourceTag} goes into the RECEIVED event
     * (e.g. MULTIPART, WHATSAPP).
     */
    public FileRecord accept(InputStream in, String originalName, String contentType, FileCategory category,
                             Context context, DataOrigin dataOrigin, String idempotencyKey, FileSource source,
                             String actor, String sourceTag) throws IOException {
        Optional<FileRecord> earlier = existing(idempotencyKey);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        long maxBytes = stagingProperties.maxSizeBytes();
        UUID fileId = UUID.randomUUID();
        Path stagingDir = stagingProperties.resolvedDirectory();
        Path tmpPath = stagingDir.resolve(fileId + ".tmp");
        Path finalPath = stagingDir.resolve(fileId.toString());

        MessageDigest digest = Sha256.newDigest();
        byte[] head = new byte[ClassificationRequest.HEAD_BYTES];
        int headOffset = 0;
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream out = Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new TooLargeException(maxBytes);
                }
                if (headOffset < ClassificationRequest.HEAD_BYTES) {
                    int toCopy = Math.min(read, ClassificationRequest.HEAD_BYTES - headOffset);
                    System.arraycopy(buffer, 0, head, headOffset, toCopy);
                    headOffset += toCopy;
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmpPath);
            throw e;
        }
        try {
            Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String sha256Hex = Sha256.hex(digest);
        String name = originalName != null ? originalName : "upload.bin";
        Instant now = Instant.now();
        byte[] actualHead = headOffset == head.length ? head : java.util.Arrays.copyOf(head, headOffset);
        Context ctx = context == null ? Context.empty() : context;

        ClassificationResult classResult = classificationService.classify(
                new ClassificationRequest(category, name, contentType, actualHead, ctx.asMap()));
        FileCategory resolved = classResult.category() != null ? classResult.category()
                : FileCategory.CONNECTION_DOCUMENT;

        FileRecord record = new FileRecord(fileId, resolved, name, total,
                dataOrigin != null ? dataOrigin : DataOrigin.REAL, source, actor, now);
        record.setContentType(contentType);
        record.setSha256Plain(sha256Hex);
        record.setIdempotencyKey(idempotencyKey);
        record.setTraceId(UUID.randomUUID().toString());
        fileRecordRepository.save(record);

        FileContext fc = new FileContext(fileId);
        fc.setZone(ctx.zone());
        fc.setStation(ctx.station());
        fc.setEquipment(ctx.equipment());
        fc.setMeterId(ctx.meterId());
        fc.setEventType(ctx.eventType());
        fc.setEventTime(ctx.eventTime());
        fc.setDescription(ctx.description());
        fileContextRepository.save(fc);

        ObjectNode details = objectMapper.createObjectNode()
                .put("originalName", name)
                .put("sizeBytes", total)
                .put("sha256Plain", sha256Hex)
                .put("source", sourceTag);
        fileEventRepository.save(new FileEvent(fileId, FileEventType.RECEIVED, actor, details, record.getTraceId(),
                now));

        eventPublisher.publishEvent(new UploadCompletedEvent(
                fileId, finalPath, total, sha256Hex, resolved, record, now).withActor(actor));
        log.info("File accepted: fileId={}, size={}, sha256={}, category={}, source={}", fileId, total, sha256Hex,
                resolved, sourceTag);
        return record;
    }
}
