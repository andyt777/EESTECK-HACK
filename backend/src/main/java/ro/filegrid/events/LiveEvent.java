package ro.filegrid.events;

import java.time.Instant;
import java.util.UUID;
import ro.filegrid.file.FileCategory;
import ro.filegrid.storage.StorageType;

/**
 * One live state change of a file, broadcast on {@code filegrid.events} and
 * streamed to browsers over SSE.
 *
 * @param status           a {@link ro.filegrid.file.FileStatus} name, or one of the transient
 *                         markers {@link #FALLBACK} and {@link #MISMATCH}
 * @param storage          storage involved, or {@code null}
 * @param message          short Romanian text of Agent 01 describing the real state
 * @param bytesTransferred bytes already sent (transfer events), otherwise {@code null}
 * @param totalBytes       size of the file
 */
public record LiveEvent(UUID fileId, FileCategory category, String status, StorageType storage, String message,
                        Long bytesTransferred, Long totalBytes, Instant timestamp, String traceId) {

    /** The primary storage gave up; the transfer moves to the next storage. */
    public static final String FALLBACK = "FALLBACK";

    /** The read-back hash differed from the original; the transfer is repeated. */
    public static final String MISMATCH = "MISMATCH";
}
