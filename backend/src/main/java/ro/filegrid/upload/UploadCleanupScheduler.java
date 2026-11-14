package ro.filegrid.upload;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import me.desair.tus.server.TusFileUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileStatus;

/**
 * Scheduled cleaner that purges expired partial tus uploads and abandoned staging files
 * older than 24 hours, while explicitly preserving files with status {@code PENDING} per Requirement R3.
 */
@Component
@Profile("api")
public class UploadCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(UploadCleanupScheduler.class);

    private final StagingProperties stagingProperties;
    private final FileRecordRepository fileRecordRepository;
    private final Optional<TusFileUploadService> tusFileUploadService;

    public UploadCleanupScheduler(
            StagingProperties stagingProperties,
            FileRecordRepository fileRecordRepository,
            @Autowired(required = false) TusFileUploadService tusFileUploadService
    ) {
        this.stagingProperties = stagingProperties;
        this.fileRecordRepository = fileRecordRepository;
        this.tusFileUploadService = Optional.ofNullable(tusFileUploadService);
    }

    @Scheduled(
            fixedDelayString = "${filegrid.staging.cleanup-interval:1h}",
            initialDelayString = "${filegrid.staging.cleanup-initial-delay:5m}"
    )
    public void cleanup() {
        log.info("Starting upload cleanup cycle...");

        // 1. Purge expired tus chunks via TusFileUploadService
        purgeTusUploads();

        // 2. Purge orphaned and abandoned files in staging directory
        purgeOrphanedStagingFiles();

        log.info("Upload cleanup cycle completed.");
    }

    public void purgeTusUploads() {
        if (tusFileUploadService.isEmpty()) {
            return;
        }
        try {
            log.info("Purging expired tus uploads via TusFileUploadService...");
            tusFileUploadService.get().cleanup();
        } catch (Exception e) {
            log.error("Failed to execute tus upload cleanup: {}", e.getMessage(), e);
        }
    }

    public void purgeOrphanedStagingFiles() {
        Path stagingDir = stagingProperties.resolvedDirectory();
        if (!Files.isDirectory(stagingDir)) {
            return;
        }

        Instant cutoff = Instant.now().minus(stagingProperties.cleanupOlderThan());

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDir)) {
            for (Path entry : stream) {
                // Do not delete subdirectories (e.g. /data/staging/tus)
                if (Files.isDirectory(entry)) {
                    continue;
                }

                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    Instant lastModified = attrs.lastModifiedTime().toInstant();

                    // If file is newer than the cutoff, keep it
                    if (lastModified.isAfter(cutoff)) {
                        continue;
                    }

                    String filename = entry.getFileName().toString();

                    // Case A: Partial multipart upload (.tmp) older than cutoff
                    if (filename.endsWith(".tmp")) {
                        log.info("Purging abandoned temporary multipart file: {}", filename);
                        safelyDelete(entry);
                        continue;
                    }

                    // Case B: Completed file name (UUID format)
                    try {
                        UUID fileId = UUID.fromString(filename);
                        Optional<FileRecord> recordOpt = fileRecordRepository.findById(fileId);

                        if (recordOpt.isEmpty()) {
                            log.info("Purging orphaned staging file with no database record: {}", filename);
                            safelyDelete(entry);
                        } else {
                            FileRecord record = recordOpt.get();
                            // If stored and verified, staging file cleanup was missed
                            if (record.getStatus() == FileStatus.STORED_VERIFIED) {
                                log.info("Purging lingering staging file for verified file {}: {}", fileId, filename);
                                safelyDelete(entry);
                            } else if (record.getStatus() == FileStatus.PENDING) {
                                // Explicitly retain PENDING files per Requirement R3
                                log.debug("Retaining staging file {} in PENDING state", fileId);
                            } else if (record.getStatus() == FileStatus.RECEIVED || record.getStatus() == FileStatus.ANALYZING) {
                                log.warn("Purging stalled ingest file {} (status={}) older than cutoff", fileId, record.getStatus());
                                safelyDelete(entry);
                                record.setStatus(FileStatus.TRANSFER_FAILED);
                                fileRecordRepository.save(record);
                            }
                        }
                    } catch (IllegalArgumentException notAUuid) {
                        // Non-UUID unknown file older than cutoff
                        log.info("Purging unrecognized aged file in staging: {}", filename);
                        safelyDelete(entry);
                    }
                } catch (IOException e) {
                    log.warn("Cannot inspect attributes of {}: {}", entry.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to read staging directory during cleanup: {}", e.getMessage(), e);
        }
    }

    private void safelyDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("Failed to delete staging file '{}': {}", path, e.getMessage());
        }
    }
}
