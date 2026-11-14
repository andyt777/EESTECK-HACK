package ro.filegrid.messaging;

import java.time.Instant;
import java.util.UUID;
import ro.filegrid.file.FileCategory;
import ro.filegrid.storage.StorageType;

/**
 * A transfer job: move one staged file to its selected storage. The database
 * stays the source of truth; the job only says which file to process, so a
 * duplicate or stale job is harmless (the worker re-reads the file record).
 *
 * @param stagingPath     absolute path of the file in the shared staging volume
 * @param sha256Plain     SHA-256 of the original content (verified after read-back)
 * @param selectedStorage storage chosen by Agent 01
 */
public record TransferJob(UUID fileId, FileCategory category, String stagingPath, long sizeBytes,
                          String sha256Plain, StorageType selectedStorage, String storageKey,
                          String traceId, Instant enqueuedAt) {
}
