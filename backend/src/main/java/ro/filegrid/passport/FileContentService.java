package ro.filegrid.passport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ro.filegrid.events.FileLifecycle;
import ro.filegrid.events.LiveEvent;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.IntegrityStatus;
import ro.filegrid.storage.Sha256;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.database.StoreDbConfig;
import ro.filegrid.text.Texts;

/**
 * Reads stored files back and decides whether what comes out of the storage
 * is still what FileGrid received (SHA-256 recomputed while streaming).
 */
@Service
@Profile("api")
public class FileContentService {

    private static final Logger log = LoggerFactory.getLogger(FileContentService.class);
    private static final int BUFFER = 64 * 1024;

    private final FileRecordRepository files;
    private final FileLifecycle lifecycle;
    private final JdbcTemplate storeDb;
    private final ApplicationEventPublisher publisher;
    private final Texts texts;
    private final Counter mismatches;
    private final Map<StorageType, StorageAdapter> adapters = new EnumMap<>(StorageType.class);

    public FileContentService(FileRecordRepository files, FileLifecycle lifecycle,
                              @Qualifier(StoreDbConfig.QUALIFIER) JdbcTemplate storeDb,
                              List<StorageAdapter> adapters, ApplicationEventPublisher publisher, Texts texts,
                              MeterRegistry registry) {
        this.files = files;
        this.lifecycle = lifecycle;
        this.storeDb = storeDb;
        this.publisher = publisher;
        this.texts = texts;
        this.mismatches = Counter.builder("filegrid_integrity_mismatch_total")
                .description("Files whose content read back from the storage did not match the original SHA-256")
                .register(registry);
        adapters.forEach(adapter -> this.adapters.put(adapter.type(), adapter));
    }

    /** A content mismatch found while reading a file back; picked up by the incident center. */
    public record IntegrityMismatchDetected(UUID fileId, String fileName, FileCategory category, StorageType storage,
                                            String expectedSha256, String actualSha256, String actor, String trigger,
                                            Instant at) {
    }

    /** @param intact {@code true} when the content read back has the expected SHA-256 */
    public record VerifyResult(UUID fileId, boolean intact, IntegrityStatus integrityStatus, String expectedSha256,
                               String actualSha256, Instant verifiedAt, StorageType storage, String message) {
    }

    public FileRecord require(UUID id) {
        return files.findById(id).orElseThrow(
                () -> new ProblemException(HttpStatus.NOT_FOUND, "file-not-found", texts.get("file.error.notFound")));
    }

    /** The file must be stored before its content can be read. */
    public void requireStored(FileRecord file) {
        if (file.getStatus() != FileStatus.STORED_VERIFIED || file.getStorageType() == null
                || file.getStorageKey() == null) {
            throw new ProblemException(HttpStatus.CONFLICT, "file-not-stored", texts.get("file.error.notStored"));
        }
    }

    /** The hash the stored bytes must have. */
    public String expectedHash(FileRecord file) {
        return file.getSha256Stored() != null ? file.getSha256Stored() : file.getSha256Plain();
    }

    /** Meter readings ingested in batches live in their own table of the Database storage. */
    private static boolean isMeterBlob(FileRecord file) {
        return file.getCategory() == FileCategory.METER_READING && file.getIdempotencyKey() != null
                && file.getStorageType() == StorageType.DATABASE;
    }

    /** Opens the stored content. The caller closes the stream. */
    public InputStream open(FileRecord file) {
        if (isMeterBlob(file)) {
            List<byte[]> rows = storeDb.query("SELECT content FROM meter_reading_blob WHERE reading_id = ?",
                    (rs, i) -> rs.getBytes(1), file.getIdempotencyKey());
            if (rows.isEmpty()) {
                throw new StorageObjectNotFoundException(StorageType.DATABASE, file.getStorageKey());
            }
            return new ByteArrayInputStream(rows.get(0)); // a reading is at most 64 KB
        }
        StorageAdapter adapter = adapters.get(file.getStorageType());
        if (adapter == null) {
            throw new StorageException("No adapter for storage " + file.getStorageType());
        }
        return adapter.get(file.getStorageKey());
    }

    /** Read-back of the whole object with the SHA-256 recomputed in flight; the result is recorded. */
    public VerifyResult verify(UUID id, String actor, String trigger) {
        FileRecord file = require(id);
        requireStored(file);
        String actual;
        try (InputStream in = open(file)) {
            MessageDigest digest = Sha256.newDigest();
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            actual = Sha256.hex(digest);
        } catch (StorageObjectNotFoundException e) {
            actual = null; // the object is gone: the stored copy is not valid
        } catch (IOException | StorageException e) {
            log.warn("File {} could not be read back from {}: {}", id, file.getStorageType(), e.getMessage());
            throw new ProblemException(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable",
                    texts.get("file.error.storageUnavailable", lifecycle.storageName(file.getStorageType())));
        }
        return record(file, actual, actor, trigger);
    }

    /** Records the outcome of a read-back: integrity status, audit entry, live event, incident signal. */
    public VerifyResult record(FileRecord file, String actualSha256, String actor, String trigger) {
        String expected = expectedHash(file);
        boolean intact = expected != null && expected.equalsIgnoreCase(actualSha256);
        Instant now = Instant.now();
        boolean wasMismatch = file.getIntegrityStatus() == IntegrityStatus.MISMATCH;
        file.setIntegrityStatus(intact ? IntegrityStatus.VERIFIED_INTACT : IntegrityStatus.MISMATCH);
        file.setLastVerifiedAt(now);
        FileRecord saved = files.save(file);
        ObjectNode details = lifecycle.details().put("storage", saved.getStorageType().name())
                .put("trigger", trigger).put("expectedSha256", expected);
        if (actualSha256 == null) {
            details.put("objectMissing", true);
        } else {
            details.put("readBackSha256", actualSha256);
        }
        String message;
        if (intact) {
            details.put("result", IntegrityStatus.VERIFIED_INTACT.name());
            lifecycle.audit(saved, FileEventType.VERIFIED, actor, details);
            message = texts.get("integrity.intact");
            lifecycle.broadcast(saved, "INTEGRITY_VERIFIED", saved.getStorageType(), message, null);
        } else {
            details.put("result", IntegrityStatus.MISMATCH.name());
            lifecycle.audit(saved, FileEventType.INTEGRITY_INCIDENT, actor, details);
            message = texts.get("integrity.mismatch");
            lifecycle.broadcast(saved, LiveEvent.MISMATCH, saved.getStorageType(), message, null);
            if (!wasMismatch) {
                mismatches.increment();
                publisher.publishEvent(new IntegrityMismatchDetected(saved.getId(), saved.getOriginalName(),
                        saved.getCategory(), saved.getStorageType(), expected, actualSha256, actor, trigger, now));
            }
            log.warn("Integrity mismatch for file {} on {} (trigger: {})", saved.getId(), saved.getStorageType(),
                    trigger);
        }
        return new VerifyResult(saved.getId(), intact, saved.getIntegrityStatus(), expected, actualSha256, now,
                saved.getStorageType(), message);
    }
}
