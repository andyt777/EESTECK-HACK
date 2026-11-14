package ro.filegrid.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import ro.filegrid.events.FileLifecycle;
import ro.filegrid.events.LiveEvent;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.IntegrityStatus;
import ro.filegrid.file.RoutingDecisionRepository;
import ro.filegrid.messaging.TransferJob;
import ro.filegrid.policy.Policy;
import ro.filegrid.policy.PolicyService;
import ro.filegrid.storage.Sha256;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageEligibilityException;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.StoredObject;
import ro.filegrid.storage.StreamSource;
import ro.filegrid.storage.s3.MultipartCheckpoint;
import ro.filegrid.storage.s3.S3StorageAdapter;
import ro.filegrid.upload.StagingProperties;

/**
 * Agent 01 executing a transfer: staging → storage, streamed, then read back
 * and compared with the original SHA-256. Only a matching read-back makes a
 * file STORED_VERIFIED; only then is the staged copy deleted.
 *
 * <p>Storage errors are retried with exponential backoff and jitter (policy
 * {@code retry}); when the attempts on one storage are used up the transfer
 * falls back to the next storage of the policy; when every storage failed the
 * file becomes PENDING and stays in staging until a storage is back.
 *
 * <p>The database is the source of truth, never the job: a duplicate or stale
 * job for a file that is already verified is acknowledged without any write.
 */
@Service
@Profile("worker")
public class TransferWorker {

    /** Actor of the audit entries written by the transfer agent. */
    public static final String ACTOR = "agent-01";

    private static final Logger log = LoggerFactory.getLogger(TransferWorker.class);
    private static final int READ_BUFFER = 64 * 1024;
    private static final Policy.Retry DEFAULT_RETRY = new Policy.Retry(3, Duration.ofSeconds(1), Duration.ofSeconds(10));

    private final FileRecordRepository files;
    private final RoutingDecisionRepository decisions;
    private final FileLifecycle lifecycle;
    private final PolicyService policies;
    private final StagingProperties staging;
    private final TransferLock locks;
    private final MultipartTransferStore multipart;
    private final Map<StorageType, StorageAdapter> adapters = new EnumMap<>(StorageType.class);
    private final Sleeper sleeper;

    @Autowired
    public TransferWorker(FileRecordRepository files, RoutingDecisionRepository decisions, FileLifecycle lifecycle,
                          PolicyService policies, StagingProperties staging, TransferLock locks,
                          MultipartTransferStore multipart, List<StorageAdapter> adapters) {
        this(files, decisions, lifecycle, policies, staging, locks, multipart, adapters, Thread::sleep);
    }

    TransferWorker(FileRecordRepository files, RoutingDecisionRepository decisions, FileLifecycle lifecycle,
                   PolicyService policies, StagingProperties staging, TransferLock locks,
                   MultipartTransferStore multipart, List<StorageAdapter> adapters, Sleeper sleeper) {
        this.files = files;
        this.decisions = decisions;
        this.lifecycle = lifecycle;
        this.policies = policies;
        this.staging = staging;
        this.locks = locks;
        this.multipart = multipart;
        this.sleeper = sleeper;
        adapters.forEach(adapter -> this.adapters.put(adapter.type(), adapter));
    }

    /** Waits between attempts; replaced in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** How a job ended, for the listener (every outcome is acknowledged). */
    public enum Result {
        STORED_VERIFIED, ALREADY_VERIFIED, BUSY_ELSEWHERE, UNKNOWN_FILE, PENDING, FAILED
    }

    /**
     * Processes one job.
     *
     * @throws InterruptedException when the worker is shutting down; the job must be requeued
     */
    public Result process(TransferJob job) throws InterruptedException {
        UUID fileId = job.fileId();
        Optional<FileRecord> found = files.findById(fileId);
        if (found.isEmpty()) {
            log.warn("Job for unknown file {} acknowledged without action", fileId);
            return Result.UNKNOWN_FILE;
        }
        if (found.get().getStatus() == FileStatus.STORED_VERIFIED) {
            log.info("File {} is already stored and verified; duplicate job acknowledged", fileId);
            return Result.ALREADY_VERIFIED;
        }
        Optional<TransferLock.Held> lock = locks.tryAcquire(fileId);
        if (lock.isEmpty()) {
            log.info("File {} is being transferred by another worker; duplicate job acknowledged", fileId);
            return Result.BUSY_ELSEWHERE;
        }
        try (TransferLock.Held ignored = lock.get()) {
            // Re-read under the lock: another worker may have finished meanwhile.
            FileRecord file = files.findById(fileId).orElseThrow();
            if (file.getStatus() == FileStatus.STORED_VERIFIED) {
                return Result.ALREADY_VERIFIED;
            }
            return transfer(file, job);
        }
    }

    private Result transfer(FileRecord file, TransferJob job) throws InterruptedException {
        Path stagingFile = staging.resolvedDirectory().resolve(file.getId().toString());
        String key = storageKey(file, job);
        List<StorageType> chain = chain(file, job);
        Policy.Retry retry = retryPolicy(file);
        Transfer transfer = new Transfer(file, key, stagingFile);
        boolean interrupted = file.getStatus() == FileStatus.TRANSFERRING
                || file.getStatus() == FileStatus.TRANSFER_RESUMED;

        if (!Files.exists(stagingFile)) {
            return stagingMissing(transfer, chain);
        }
        if (interrupted) {
            transfer.markResumed(chain.get(0));
        }

        StorageType previous = null;
        for (StorageType storage : chain) {
            if (previous != null) {
                fallback(transfer, previous, storage);
            }
            Attempts outcome = tryStorage(transfer, storage, retry);
            if (outcome == Attempts.VERIFIED) {
                return Result.STORED_VERIFIED;
            }
            if (outcome == Attempts.STAGING_LOST) {
                return stagingMissing(transfer, List.of());
            }
            if (outcome == Attempts.STAGING_CORRUPT) {
                break;
            }
            previous = storage;
        }

        if (transfer.stagingCorrupt || transfer.onlyMismatches()) {
            transfer.file = lifecycle.changeStatus(transfer.file, FileStatus.TRANSFER_FAILED,
                    FileEventType.STATUS_CHANGED, ACTOR,
                    lifecycle.details().put("reason", transfer.stagingCorrupt ? "STAGING_CORRUPT" : "INTEGRITY_MISMATCH")
                            .put("attempts", transfer.mismatches),
                    null, lifecycle.message("TRANSFER_FAILED", lifecycle.message("INTEGRITY_FAILED")));
            return Result.FAILED;
        }
        transfer.file = lifecycle.changeStatus(transfer.file, FileStatus.PENDING, FileEventType.STATUS_CHANGED,
                ACTOR, lifecycle.details().put("reason", "ALL_STORAGES_FAILED")
                        .put("lastError", transfer.lastError == null ? "" : transfer.lastError),
                null, lifecycle.message("PENDING"));
        log.warn("File {}: every storage failed ({}); kept PENDING in staging", file.getId(), transfer.lastError);
        return Result.PENDING;
    }

    private enum Attempts {
        VERIFIED, EXHAUSTED, INELIGIBLE, STAGING_LOST, STAGING_CORRUPT
    }

    /** Up to {@code retry.maxAttempts} attempts on one storage. */
    private Attempts tryStorage(Transfer transfer, StorageType storage, Policy.Retry retry)
            throws InterruptedException {
        StorageAdapter adapter = adapters.get(storage);
        if (adapter == null) {
            transfer.lastError = "no adapter for " + storage;
            return Attempts.INELIGIBLE;
        }
        Backoff backoff = new Backoff(retry.initialBackoff(), retry.maxBackoff());
        int maxAttempts = Math.max(1, retry.maxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!Files.exists(transfer.stagingFile)) {
                return Attempts.STAGING_LOST;
            }
            transfer.startAttempt(storage, attempt);
            boolean storageError = false;
            try {
                StoredObject stored = write(transfer, adapter);
                String expected = transfer.file.getSha256Plain();
                String readBack = readBackSha256(adapter, transfer.key);
                if (readBack.equals(expected)) {
                    verified(transfer, storage, stored, readBack);
                    return Attempts.VERIFIED;
                }
                // When the bytes read from staging already differ from the original, the staged
                // copy is damaged, and no retry or other storage can fix that.
                boolean stagingCorrupt = !stored.sha256().equals(expected);
                mismatch(transfer, adapter, readBack, attempt, maxAttempts, !stagingCorrupt);
                if (stagingCorrupt) {
                    transfer.stagingCorrupt = true;
                    return Attempts.STAGING_CORRUPT;
                }
            } catch (StorageEligibilityException e) {
                transfer.lastError = e.getMessage();
                transfer.storageErrors++;
                log.info("File {} cannot be stored on {}: {}", transfer.file.getId(), storage, e.getMessage());
                return Attempts.INELIGIBLE;
            } catch (StorageException | UncheckedIOException e) {
                if (!Files.exists(transfer.stagingFile)) {
                    return Attempts.STAGING_LOST;
                }
                transfer.lastError = e.getMessage();
                transfer.storageErrors++;
                storageError = true;
                log.warn("File {}: {} attempt {} of {} failed: {}", transfer.file.getId(), storage, attempt,
                        maxAttempts, e.getMessage());
            }
            if (attempt < maxAttempts) {
                Duration delay = backoff.delayAfter(attempt);
                if (storageError) {
                    lifecycle.broadcast(transfer.file, transfer.file.getStatus().name(), storage,
                            lifecycle.message("RETRY", lifecycle.storageName(storage), attempt, maxAttempts,
                                    String.format(java.util.Locale.ROOT, "%.1f", delay.toMillis() / 1000.0)),
                            transfer.bytesSent);
                }
                sleeper.sleep(delay.toMillis());
            }
        }
        return Attempts.EXHAUSTED;
    }

    private StoredObject write(Transfer transfer, StorageAdapter adapter) {
        FileRecord file = transfer.file;
        StreamSource source = () -> new ProgressInputStream(Files.newInputStream(transfer.stagingFile),
                file.getSizeBytes(), bytes -> transfer.progress(adapter.type(), bytes));
        Map<String, String> metadata = Map.of("filegrid-file-id", file.getId().toString(),
                "filegrid-sha256", file.getSha256Plain() == null ? "" : file.getSha256Plain());
        if (adapter instanceof S3StorageAdapter s3) {
            MultipartCheckpoint checkpoint = multipart.forFile(file.getId(),
                    () -> transfer.markResumed(StorageType.S3));
            return s3.putResumable(transfer.key, source, file.getSizeBytes(), metadata, checkpoint);
        }
        return adapter.put(transfer.key, source, file.getSizeBytes(), metadata);
    }

    private void verified(Transfer transfer, StorageType storage, StoredObject stored, String readBack) {
        FileRecord file = transfer.file;
        Instant now = Instant.now();
        file.setStorageLocation(storage, transfer.key);
        file.setSha256Stored(readBack);
        file.setIntegrityStatus(IntegrityStatus.VERIFIED_INTACT);
        file.setLastVerifiedAt(now);
        file.setStoredAt(now);
        lifecycle.audit(file, FileEventType.STORED, ACTOR, lifecycle.details()
                .put("storage", storage.name()).put("bytesWritten", stored.sizeWritten())
                .put("sha256Sent", stored.sha256()));
        transfer.file = lifecycle.changeStatus(file, FileStatus.STORED_VERIFIED, FileEventType.VERIFIED, ACTOR,
                lifecycle.details().put("storage", storage.name()).put("sha256", readBack)
                        .put("result", IntegrityStatus.VERIFIED_INTACT.name()),
                storage, lifecycle.message("STORED_VERIFIED"), file.getSizeBytes());
        if (storage != StorageType.S3 && adapters.get(StorageType.S3) instanceof S3StorageAdapter s3) {
            // Verified elsewhere: an unfinished S3 multipart upload of this file is abandoned.
            try {
                s3.discardResumable(transfer.key, multipart.forFile(file.getId(), () -> { }));
            } catch (RuntimeException e) {
                log.debug("Unfinished S3 upload of {} not aborted: {}", file.getId(), e.getMessage());
            }
        }
        multipart.delete(file.getId());
        try {
            Files.deleteIfExists(transfer.stagingFile);
        } catch (IOException e) {
            // Verified and recorded: a leftover staged copy is removed by the staging cleanup.
            log.warn("File {} verified but its staged copy could not be deleted: {}", file.getId(), e.getMessage());
        }
        log.info("File {} stored on {} and verified ({} bytes)", file.getId(), storage, stored.sizeWritten());
    }

    private void mismatch(Transfer transfer, StorageAdapter adapter, String readBack, int attempt, int maxAttempts,
                          boolean willRetry) {
        FileRecord file = transfer.file;
        transfer.mismatches++;
        file.setIntegrityStatus(IntegrityStatus.MISMATCH);
        transfer.file = files.save(file);
        lifecycle.audit(transfer.file, FileEventType.INTEGRITY_INCIDENT, ACTOR, lifecycle.details()
                .put("storage", adapter.type().name()).put("expectedSha256", file.getSha256Plain())
                .put("readBackSha256", readBack).put("attempt", attempt).put("maxAttempts", maxAttempts)
                .put("stagingCorrupt", !willRetry));
        lifecycle.broadcast(transfer.file, LiveEvent.MISMATCH, adapter.type(),
                lifecycle.message(willRetry ? "MISMATCH" : "MISMATCH_FINAL"), transfer.bytesSent);
        log.warn("File {}: read-back from {} does not match the original SHA-256 (attempt {} of {})",
                file.getId(), adapter.type(), attempt, maxAttempts);
        try {
            adapter.delete(transfer.key);
        } catch (RuntimeException e) {
            log.warn("Could not delete the mismatching copy of {} on {}: {}", file.getId(), adapter.type(),
                    e.getMessage());
        }
    }

    private void fallback(Transfer transfer, StorageType from, StorageType to) {
        FileRecord file = transfer.file;
        String reason = lifecycle.message("FALLBACK_REASON", lifecycle.storageName(from),
                transfer.lastError == null ? "" : transfer.lastError);
        // An S3 multipart upload in progress is kept: if the file comes back to S3 later
        // (after PENDING), the transfer continues from its last confirmed part.
        decisions.findFirstByFileIdOrderByDecidedAtDesc(file.getId()).ifPresent(decision -> {
            decision.setSelectedStorage(to);
            decision.setFallback(reason);
            decisions.save(decision);
        });
        file.setStorageLocation(to, transfer.key);
        transfer.file = files.save(file);
        lifecycle.audit(transfer.file, FileEventType.FALLBACK, ACTOR, lifecycle.details()
                .put("from", from.name()).put("to", to.name()).put("reason", reason));
        lifecycle.broadcast(transfer.file, LiveEvent.FALLBACK, to,
                lifecycle.message("FALLBACK", lifecycle.storageName(from), lifecycle.storageName(to)),
                0L);
        log.info("File {}: falling back from {} to {}", file.getId(), from, to);
    }

    /**
     * The staged copy is gone. If a storage already holds an intact copy (the
     * worker stopped between writing and recording), the file is verified from
     * it; otherwise the transfer cannot go on.
     */
    private Result stagingMissing(Transfer transfer, List<StorageType> candidates) {
        FileRecord file = transfer.file;
        List<StorageType> where = new ArrayList<>();
        if (file.getStorageType() != null) {
            where.add(file.getStorageType());
        }
        candidates.stream().filter(s -> !where.contains(s)).forEach(where::add);
        for (StorageType storage : where) {
            StorageAdapter adapter = adapters.get(storage);
            if (adapter == null || file.getSha256Plain() == null) {
                continue;
            }
            try {
                String readBack = readBackSha256(adapter, transfer.key);
                if (readBack.equals(file.getSha256Plain())) {
                    verified(transfer, storage, new StoredObject(transfer.key, file.getSizeBytes(), readBack),
                            readBack);
                    return Result.STORED_VERIFIED;
                }
            } catch (StorageException | UncheckedIOException e) {
                log.debug("No intact copy of {} on {}: {}", file.getId(), storage, e.getMessage());
            }
        }
        transfer.file = lifecycle.changeStatus(file, FileStatus.TRANSFER_FAILED, FileEventType.STATUS_CHANGED,
                ACTOR, lifecycle.details().put("reason", "STAGING_MISSING"), null,
                lifecycle.message("TRANSFER_FAILED", lifecycle.message("STAGING_MISSING")));
        log.error("File {}: the staged copy is missing and no storage holds an intact copy", file.getId());
        return Result.FAILED;
    }

    static String readBackSha256(StorageAdapter adapter, String key) {
        MessageDigest digest = Sha256.newDigest();
        byte[] buffer = new byte[READ_BUFFER];
        try (InputStream in = adapter.get(key)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (StorageObjectNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new StorageException("Read-back of " + key + " from " + adapter.type() + " failed: "
                    + e.getMessage(), e);
        }
        return Sha256.hex(digest);
    }

    /**
     * Storages to try, in order: the one Agent 01 selected, then the policy
     * fallbacks that come after it ([primary, fallbacks...]).
     */
    List<StorageType> chain(FileRecord file, TransferJob job) {
        StorageType selected = file.getStorageType() != null ? file.getStorageType() : job.selectedStorage();
        List<StorageType> order = new ArrayList<>();
        policies.activeIfPresent().map(active -> active.policy().category(file.getCategory())).ifPresent(rules -> {
            order.add(rules.primary());
            rules.fallbacks().stream().filter(s -> !order.contains(s)).forEach(order::add);
        });
        if (selected == null) {
            return order.isEmpty() ? List.of() : order;
        }
        int index = order.indexOf(selected);
        if (index < 0) {
            List<StorageType> chain = new ArrayList<>();
            chain.add(selected);
            order.stream().filter(s -> s != selected).forEach(chain::add);
            return chain;
        }
        return List.copyOf(order.subList(index, order.size()));
    }

    /** The key recorded by Agent 01 if valid, else the job's, else a fresh one of the standard layout. */
    static String storageKey(FileRecord file, TransferJob job) {
        String segment = file.getCategory().storageSegment();
        for (String candidate : new String[] {file.getStorageKey(), job.storageKey()}) {
            if (candidate == null) {
                continue;
            }
            try {
                if (StorageKeys.segmentOf(candidate).equals(segment)) {
                    return candidate;
                }
            } catch (IllegalArgumentException ignored) {
                // not a usable key: try the next one
            }
        }
        return StorageKeys.forCategory(file.getCategory(), file.getReceivedAt(), file.getId());
    }

    private Policy.Retry retryPolicy(FileRecord file) {
        return policies.activeIfPresent()
                .map(active -> active.policy().category(file.getCategory()))
                .map(Policy.CategoryPolicy::retry)
                .orElse(DEFAULT_RETRY);
    }

    /** State of one transfer across attempts and storages. */
    private final class Transfer {
        FileRecord file;
        final String key;
        final Path stagingFile;
        int mismatches;
        int storageErrors;
        boolean stagingCorrupt;
        String lastError;
        long bytesSent;
        boolean resumedAnnounced;

        Transfer(FileRecord file, String key, Path stagingFile) {
            this.file = file;
            this.key = key;
            this.stagingFile = stagingFile;
        }

        boolean onlyMismatches() {
            return mismatches > 0 && storageErrors == 0;
        }

        void startAttempt(StorageType storage, int attempt) {
            bytesSent = 0;
            file.setStorageLocation(storage, key);
            FileStatus status = file.getStatus();
            if (status != FileStatus.TRANSFERRING && status != FileStatus.TRANSFER_RESUMED) {
                file = lifecycle.changeStatus(file, FileStatus.TRANSFERRING, FileEventType.TRANSFER_STARTED, ACTOR,
                        lifecycle.details().put("storage", storage.name()).put("attempt", attempt), storage,
                        lifecycle.message("TRANSFERRING"), 0L);
            } else {
                file = files.save(file);
                lifecycle.broadcast(file, status.name(), storage, lifecycle.message("TRANSFERRING"), 0L);
            }
        }

        /** An interrupted transfer is being continued (announced once per job). */
        void markResumed(StorageType storage) {
            if (resumedAnnounced) {
                return;
            }
            resumedAnnounced = true;
            ObjectNode details = lifecycle.details().put("storage", storage.name());
            file = lifecycle.changeStatus(file, FileStatus.TRANSFER_RESUMED, FileEventType.TRANSFER_RESUMED, ACTOR,
                    details, storage, lifecycle.message("TRANSFER_RESUMED"), bytesSent);
        }

        void progress(StorageType storage, long bytes) {
            bytesSent = Math.min(bytes, file.getSizeBytes());
            lifecycle.broadcast(file, file.getStatus().name(), storage, lifecycle.message("TRANSFERRING"), bytesSent);
        }
    }
}
