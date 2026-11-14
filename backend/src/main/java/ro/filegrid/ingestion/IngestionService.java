package ro.filegrid.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ro.filegrid.classification.ClassificationRequest;
import ro.filegrid.classification.ClassificationResult;
import ro.filegrid.classification.ClassificationService;
import ro.filegrid.events.FileLifecycle;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileContext;
import ro.filegrid.file.FileContextRepository;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileSource;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.RoutingDecision;
import ro.filegrid.messaging.JobPublisher;
import ro.filegrid.messaging.TransferJob;
import ro.filegrid.routing.RoutingService;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.storage.Sha256;
import ro.filegrid.upload.StagingProperties;
import ro.filegrid.upload.UploadCompletedEvent;

/**
 * Takes a file that has just landed in staging through
 * RECEIVED → ANALYZING → CATEGORIZED → DESTINATION_SELECTED (or PENDING) and
 * queues the transfer job. Every step is saved, audited and broadcast.
 */
@Service
@Profile("api")
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final FileRecordRepository files;
    private final FileContextRepository contexts;
    private final ClassificationService classifier;
    private final RoutingService routing;
    private final JobPublisher jobs;
    private final FileLifecycle lifecycle;
    private final CurrentActorProvider actors;
    private final StagingProperties staging;

    public IngestionService(FileRecordRepository files, FileContextRepository contexts,
                            ClassificationService classifier, RoutingService routing, JobPublisher jobs,
                            FileLifecycle lifecycle, CurrentActorProvider actors, StagingProperties staging) {
        this.files = files;
        this.contexts = contexts;
        this.classifier = classifier;
        this.routing = routing;
        this.jobs = jobs;
        this.lifecycle = lifecycle;
        this.actors = actors;
        this.staging = staging;
    }

    @Async(IngestionConfiguration.EXECUTOR)
    @EventListener
    public void onUploadCompleted(UploadCompletedEvent event) {
        try {
            ingest(event);
        } catch (Exception e) {
            // The file stays in staging in its last saved state; the recovery job resumes it.
            log.error("Ingestion of file {} interrupted: {}", event.fileId(), e.getMessage(), e);
        }
    }

    /** Runs the whole ingestion of a completed upload (synchronously). */
    public void ingest(UploadCompletedEvent event) throws IOException {
        FileRecord file = files.findById(event.fileId()).orElse(null);
        if (file == null) {
            file = receive(event);
        } else {
            // Recorded by the multipart controller (RECEIVED is already in the audit trail).
            lifecycle.broadcast(file, FileStatus.RECEIVED.name(), null, lifecycle.message("RECEIVED"), null);
        }
        file = lifecycle.changeStatus(file, FileStatus.ANALYZING, FileEventType.STATUS_CHANGED,
                RoutingService.AGENT_ACTOR, null, null, lifecycle.message("ANALYZING"));
        analyzeAndRoute(file, event.category());
    }

    /**
     * Resumes a file whose ingestion was interrupted, or a PENDING file now that
     * a storage is back: classification is repeated from the staged bytes and
     * Agent 01 decides again with the current storage states.
     */
    public void resume(FileRecord file) throws IOException {
        log.info("Resuming file {} from status {}", file.getId(), file.getStatus());
        analyzeAndRoute(file, null);
    }

    private void analyzeAndRoute(FileRecord file, FileCategory declared) throws IOException {
        Path stagingFile = stagingFile(file.getId());
        if (file.getSha256Plain() == null) {
            file.setSha256Plain(sha256(stagingFile));
            file = files.save(file);
        }
        ClassificationResult classification = classify(file, stagingFile, declared);
        if (classification.category() == null) {
            lifecycle.changeStatus(file, FileStatus.TRANSFER_FAILED, FileEventType.STATUS_CHANGED,
                    RoutingService.AGENT_ACTOR, lifecycle.details().put("reason", "UNCLASSIFIED"), null,
                    lifecycle.message("TRANSFER_FAILED", lifecycle.message("UNCLASSIFIED")));
            return;
        }
        if (file.getStatus() != FileStatus.PENDING) {
            file.setCategory(classification.category());
            file = lifecycle.changeStatus(file, FileStatus.CATEGORIZED, FileEventType.CLASSIFIED,
                    RoutingService.AGENT_ACTOR,
                    lifecycle.details()
                            .put("category", classification.category().name())
                            .put("basis", classification.basis().name())
                            .put("detectedMimeType", classification.detectedMimeType())
                            .put("warnings", classification.warnings().size()),
                    null, lifecycle.message("CATEGORIZED", lifecycle.categoryName(file)));
        }
        routeAndQueue(file.getId(), classification);
    }

    private void routeAndQueue(UUID fileId, ClassificationResult classification) {
        RoutingDecision decision = routing.route(fileId, classification);
        FileRecord file = files.findById(fileId).orElseThrow();
        if (decision.getSelectedStorage() == null) {
            lifecycle.broadcast(file, FileStatus.PENDING.name(), null, lifecycle.message("PENDING"), null);
            return;
        }
        lifecycle.broadcast(file, FileStatus.DESTINATION_SELECTED.name(), decision.getSelectedStorage(),
                lifecycle.message("DESTINATION_SELECTED", decision.getSelectedStorage().name()), null);
        TransferJob job = new TransferJob(file.getId(), file.getCategory(), stagingFile(fileId).toString(),
                file.getSizeBytes(), file.getSha256Plain(), decision.getSelectedStorage(), file.getStorageKey(),
                file.getTraceId(), Instant.now());
        try {
            jobs.publish(job);
        } catch (JobPublisher.JobPublishException e) {
            log.warn("File {} kept PENDING: {}", fileId, e.getMessage());
            lifecycle.changeStatus(file, FileStatus.PENDING, FileEventType.STATUS_CHANGED,
                    RoutingService.AGENT_ACTOR, lifecycle.details().put("reason", "QUEUE_UNAVAILABLE"), null,
                    lifecycle.message("PENDING"));
        }
    }

    private FileRecord receive(UploadCompletedEvent event) {
        Map<String, String> meta = event.metadata() == null ? Map.of() : event.metadata();
        String name = event.originalFilename() == null ? "upload-" + event.fileId() : event.originalFilename();
        FileCategory provisional = event.category() == null ? FileCategory.CONNECTION_DOCUMENT : event.category();
        FileRecord file = new FileRecord(event.fileId(), provisional, name, event.sizeBytes(),
                dataOrigin(meta.get("dataOrigin")), FileSource.UI, actors.currentActor(),
                event.completedAt() == null ? Instant.now() : event.completedAt());
        file.setContentType(event.contentType());
        file.setSha256Plain(event.sha256Plain());
        file.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        file = files.save(file);

        FileContext context = new FileContext(file.getId());
        context.setZone(meta.get("zone"));
        context.setStation(meta.get("station"));
        context.setEquipment(meta.get("equipment"));
        context.setMeterId(meta.get("meterId"));
        context.setEventType(meta.get("eventType"));
        context.setDescription(meta.get("description"));
        if (meta.get("eventTime") != null) {
            try {
                context.setEventTime(Instant.parse(meta.get("eventTime")));
            } catch (RuntimeException e) {
                log.debug("Ignoring eventTime '{}' of file {}", meta.get("eventTime"), file.getId());
            }
        }
        contexts.save(context);

        return lifecycle.changeStatus(file, FileStatus.RECEIVED, FileEventType.RECEIVED, actors.currentActor(),
                lifecycle.details().put("originalName", name).put("sizeBytes", event.sizeBytes())
                        .put("source", "TUS"),
                null, lifecycle.message("RECEIVED"));
    }

    private ClassificationResult classify(FileRecord file, Path stagingFile, FileCategory declared) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(stagingFile)) {
            head = ClassificationRequest.readHead(in);
        }
        Map<String, String> context = new HashMap<>();
        contexts.findById(file.getId()).ifPresent(c -> {
            putIfPresent(context, "zone", c.getZone());
            putIfPresent(context, "station", c.getStation());
            putIfPresent(context, "equipment", c.getEquipment());
            putIfPresent(context, "meterId", c.getMeterId());
            putIfPresent(context, "eventType", c.getEventType());
            putIfPresent(context, "eventTime", c.getEventTime() == null ? null : c.getEventTime().toString());
            putIfPresent(context, "description", c.getDescription());
        });
        FileCategory declaredCategory = declared != null ? declared
                : file.getStatus() == FileStatus.PENDING || file.getStatus() == FileStatus.CATEGORIZED
                ? file.getCategory() : null;
        return classifier.classify(new ClassificationRequest(declaredCategory, file.getOriginalName(),
                file.getContentType(), head, context));
    }

    Path stagingFile(UUID fileId) {
        return staging.resolvedDirectory().resolve(fileId.toString());
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = Sha256.newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return Sha256.hex(digest);
    }

    private static DataOrigin dataOrigin(String value) {
        if (value == null || value.isBlank()) {
            return DataOrigin.REAL;
        }
        try {
            return DataOrigin.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DataOrigin.REAL;
        }
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
