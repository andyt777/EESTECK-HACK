package ro.filegrid.storage.s3;

import java.util.List;
import java.util.Optional;

/**
 * Durable record of one resumable S3 multipart upload. The adapter saves the
 * upload id when it starts and every part as soon as S3 confirms it; after a
 * crash the next attempt reads it back and continues from the next part.
 */
public interface MultipartCheckpoint {

    /** The upload in progress for this transfer, if any. */
    Optional<State> load();

    /** A new multipart upload was created (any previous state is replaced). */
    void begin(String bucket, String storageKey, String uploadId, long partSize, int totalParts);

    /** S3 confirmed one part. */
    void partConfirmed(ConfirmedPart part);

    /** The upload completed or was abandoned: nothing left to resume. */
    void clear();

    /**
     * Called once before uploading, when a previous upload is continued.
     *
     * @param bytesAlreadyStored bytes covered by the parts S3 already holds
     */
    default void resumed(long bytesAlreadyStored) {
    }

    record State(String uploadId, String bucket, String storageKey, long partSize, int totalParts,
                 List<ConfirmedPart> parts) {
    }

    record ConfirmedPart(int partNumber, String etag, long size) {
    }
}
