package ro.filegrid.upload;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileRecord;

/**
 * Application event published when an upload finishes writing to the staging volume.
 * Carries all upload details for consumption by downstream ingestion and routing.
 *
 * @param fileId           unique identifier of the staged file
 * @param stagingPath      path to the file in the shared staging volume
 * @param sizeBytes        total file size in bytes
 * @param originalFilename original name of the uploaded file
 * @param contentType      MIME type declared or detected
 * @param category         classified or declared category (may be null if unclassified)
 * @param metadata         operational context key-values (zone, station, equipment, etc.)
 * @param sha256Plain      plaintext SHA-256 hash (if calculated during upload)
 * @param fileRecord       persisted database record (if persisted by controller)
 * @param completedAt      timestamp when upload finished
 */
public record UploadCompletedEvent(
        UUID fileId,
        Path stagingPath,
        long sizeBytes,
        String originalFilename,
        String contentType,
        FileCategory category,
        Map<String, String> metadata,
        String sha256Plain,
        FileRecord fileRecord,
        Instant completedAt
) {
    public UploadCompletedEvent(
            UUID fileId,
            Path stagingPath,
            long sizeBytes,
            String originalFilename,
            String contentType,
            FileCategory category,
            Map<String, String> metadata
    ) {
        this(fileId, stagingPath, sizeBytes, originalFilename, contentType, category, metadata, null, null, Instant.now());
    }

    public UploadCompletedEvent(
            UUID fileId,
            Path stagingPath,
            long sizeBytes,
            String sha256Plain,
            FileCategory category,
            FileRecord fileRecord,
            Instant completedAt
    ) {
        this(fileId, stagingPath, sizeBytes,
                fileRecord != null ? fileRecord.getOriginalName() : null,
                fileRecord != null ? fileRecord.getContentType() : null,
                category,
                Map.of(),
                sha256Plain,
                fileRecord,
                completedAt);
    }
}
