package ro.filegrid.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import me.desair.tus.server.TusFileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileSource;
import ro.filegrid.file.FileStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadCleanupSchedulerTest {

    @TempDir
    Path tempDir;

    private StagingProperties stagingProperties;
    private FileRecordRepository fileRecordRepository;
    private TusFileUploadService tusFileUploadService;
    private UploadCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        stagingProperties = new StagingProperties(tempDir.toString(), 5368709120L, Duration.ofHours(24), Duration.ofHours(1));
        fileRecordRepository = mock(FileRecordRepository.class);
        tusFileUploadService = mock(TusFileUploadService.class);

        scheduler = new UploadCleanupScheduler(stagingProperties, fileRecordRepository, tusFileUploadService);
    }

    @Test
    void callsTusFileUploadServiceCleanup() throws IOException {
        scheduler.cleanup();
        verify(tusFileUploadService).cleanup();
    }

    @Test
    void purgesAgedTmpFileAndPreservesRecentTmpFile() throws IOException {
        Path stagingDir = stagingProperties.resolvedDirectory();

        Path oldTmp = stagingDir.resolve("old-upload.tmp");
        Files.writeString(oldTmp, "old-partial-data");
        Files.setLastModifiedTime(oldTmp, FileTime.from(Instant.now().minus(Duration.ofHours(25))));

        Path newTmp = stagingDir.resolve("recent-upload.tmp");
        Files.writeString(newTmp, "recent-partial-data");
        Files.setLastModifiedTime(newTmp, FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));

        scheduler.purgeOrphanedStagingFiles();

        assertThat(Files.exists(oldTmp)).isFalse();
        assertThat(Files.exists(newTmp)).isTrue();
    }

    @Test
    void explicitlyRetainsPendingStagingFilesOlderThanCutoff() throws IOException {
        Path stagingDir = stagingProperties.resolvedDirectory();

        UUID pendingId = UUID.randomUUID();
        Path pendingFile = stagingDir.resolve(pendingId.toString());
        Files.writeString(pendingFile, "important-data-waiting-for-storage");
        Files.setLastModifiedTime(pendingFile, FileTime.from(Instant.now().minus(Duration.ofHours(30))));

        FileRecord pendingRecord = new FileRecord(pendingId, FileCategory.DRONE_VIDEO, "flight.mp4", 1000L,
                DataOrigin.REAL, FileSource.API, "pilot", Instant.now().minus(Duration.ofHours(30)));
        pendingRecord.setStatus(FileStatus.PENDING);

        when(fileRecordRepository.findById(pendingId)).thenReturn(Optional.of(pendingRecord));

        scheduler.purgeOrphanedStagingFiles();

        // Staging file must NOT be deleted per Requirement R3
        assertThat(Files.exists(pendingFile)).isTrue();
    }

    @Test
    void purgesStoredVerifiedLingeringStagingFiles() throws IOException {
        Path stagingDir = stagingProperties.resolvedDirectory();

        UUID verifiedId = UUID.randomUUID();
        Path verifiedFile = stagingDir.resolve(verifiedId.toString());
        Files.writeString(verifiedFile, "already-stored-data");
        Files.setLastModifiedTime(verifiedFile, FileTime.from(Instant.now().minus(Duration.ofHours(26))));

        FileRecord verifiedRecord = new FileRecord(verifiedId, FileCategory.CONNECTION_DOCUMENT, "contract.pdf", 2000L,
                DataOrigin.REAL, FileSource.API, "admin", Instant.now().minus(Duration.ofHours(26)));
        verifiedRecord.setStatus(FileStatus.STORED_VERIFIED);

        when(fileRecordRepository.findById(verifiedId)).thenReturn(Optional.of(verifiedRecord));

        scheduler.purgeOrphanedStagingFiles();

        // Lingering file should be deleted
        assertThat(Files.exists(verifiedFile)).isFalse();
    }

    @Test
    void keepsStagedCopyOfFilesNotYetVerified() throws IOException {
        Path stagingDir = stagingProperties.resolvedDirectory();

        UUID stalledId = UUID.randomUUID();
        Path stalledFile = stagingDir.resolve(stalledId.toString());
        Files.writeString(stalledFile, "stalled-data");
        Files.setLastModifiedTime(stalledFile, FileTime.from(Instant.now().minus(Duration.ofHours(28))));

        FileRecord stalledRecord = new FileRecord(stalledId, FileCategory.METER_READING, "meter.csv", 500L,
                DataOrigin.REAL, FileSource.API, "admin", Instant.now().minus(Duration.ofHours(28)));
        stalledRecord.setStatus(FileStatus.RECEIVED);

        when(fileRecordRepository.findById(stalledId)).thenReturn(Optional.of(stalledRecord));

        scheduler.purgeOrphanedStagingFiles();

        // A received file is never lost: the recovery job resumes it instead.
        assertThat(Files.exists(stalledFile)).isTrue();
        assertThat(stalledRecord.getStatus()).isEqualTo(FileStatus.RECEIVED);
        verify(fileRecordRepository, never()).save(stalledRecord);
    }
}
