package ro.filegrid.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.springframework.stereotype.Component;
import ro.filegrid.file.FileEvent;
import ro.filegrid.file.FileEventRepository;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileStatus;
import ro.filegrid.storage.StorageType;
import ro.filegrid.text.Texts;

/**
 * The single place where the state of a file changes: the record is saved, an
 * entry is appended to the audit trail and the live event is broadcast, in that
 * order, so the UI never shows a state that is not in the database.
 */
@Component
public class FileLifecycle {

    private final FileRecordRepository files;
    private final FileEventRepository events;
    private final LiveEventPublisher live;
    private final Texts texts;
    private final ObjectMapper json;

    public FileLifecycle(FileRecordRepository files, FileEventRepository events, LiveEventPublisher live,
                         Texts texts, ObjectMapper json) {
        this.files = files;
        this.events = events;
        this.live = live;
        this.texts = texts;
        this.json = json;
    }

    public ObjectNode details() {
        return json.createObjectNode();
    }

    /** Agent 01 text for a key under {@code agent.*}. */
    public String message(String key, Object... args) {
        return texts.get("agent." + key, args);
    }

    public String categoryName(FileRecord file) {
        return texts.get("category." + file.getCategory().name());
    }

    /**
     * Sets the status, saves the record, appends an audit entry and broadcasts
     * the change with the given Agent 01 message.
     */
    public FileRecord changeStatus(FileRecord file, FileStatus status, FileEventType eventType, String actor,
                                   ObjectNode details, StorageType storage, String message) {
        return changeStatus(file, status, eventType, actor, details, storage, message, null);
    }

    /** Same, with the number of bytes already transferred shown in the live event. */
    public FileRecord changeStatus(FileRecord file, FileStatus status, FileEventType eventType, String actor,
                                   ObjectNode details, StorageType storage, String message, Long bytesTransferred) {
        file.setStatus(status);
        FileRecord saved = files.save(file);
        ObjectNode auditDetails = details == null ? details() : details;
        if (eventType == FileEventType.STATUS_CHANGED && !auditDetails.has("status")) {
            auditDetails.put("status", status.name());
        }
        audit(saved, eventType, actor, auditDetails);
        broadcast(saved, status.name(), storage, message, bytesTransferred);
        return saved;
    }

    /** Name of a storage as shown to users ("Database", "S3", "FTP"). */
    public String storageName(StorageType storage) {
        return texts.get("storage." + storage.name());
    }

    /** Appends an audit entry without changing the status. */
    public void audit(FileRecord file, FileEventType type, String actor, ObjectNode details) {
        events.save(new FileEvent(file.getId(), type, actor, details == null ? details() : details,
                file.getTraceId(), Instant.now()));
    }

    /**
     * Broadcasts a live event (progress, fallback, mismatch) without touching the database.
     * Without an explicit byte count, a verified file reports its full size and any other state 0.
     */
    public void broadcast(FileRecord file, String status, StorageType storage, String message, Long bytesTransferred) {
        long bytes = bytesTransferred != null ? bytesTransferred
                : FileStatus.STORED_VERIFIED.name().equals(status) ? file.getSizeBytes() : 0L;
        String traceId = file.getTraceId() != null ? file.getTraceId() : file.getId().toString().replace("-", "");
        live.publish(new LiveEvent(file.getId(), file.getCategory(), status, storage, message, bytes,
                file.getSizeBytes(), Instant.now(), traceId));
    }
}
