package ro.filegrid.ingestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileStatus;
import ro.filegrid.policy.Policy;
import ro.filegrid.policy.PolicyService;
import ro.filegrid.routing.StorageState;
import ro.filegrid.routing.StorageStateProvider;
import ro.filegrid.storage.StorageType;

/**
 * Resumes work without manual intervention:
 * <ul>
 *   <li>PENDING files, as soon as {@code storage_health} reports one of the
 *       storages of their category UP again: Agent 01 decides again with the
 *       current states and the job is queued;</li>
 *   <li>ingestions interrupted by a restart of the api (left in RECEIVED,
 *       ANALYZING or CATEGORIZED for longer than {@code staleAfter}).</li>
 * </ul>
 * A PENDING file whose storages are all still down is left alone, so no new
 * decision is written every cycle while nothing has changed.
 */
@Component
@Profile("api")
public class RecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);
    private static final EnumSet<FileStatus> INTERRUPTED =
            EnumSet.of(FileStatus.RECEIVED, FileStatus.ANALYZING, FileStatus.CATEGORIZED);

    private final FileRecordRepository files;
    private final IngestionService ingestion;
    private final StorageStateProvider storageStates;
    private final PolicyService policies;
    private final IngestionConfiguration.IngestionProperties properties;

    public RecoveryScheduler(FileRecordRepository files, IngestionService ingestion,
                             StorageStateProvider storageStates, PolicyService policies,
                             IngestionConfiguration.IngestionProperties properties) {
        this.files = files;
        this.ingestion = ingestion;
        this.storageStates = storageStates;
        this.policies = policies;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${filegrid.ingestion.recovery-interval:30s}",
            initialDelayString = "${filegrid.ingestion.recovery-interval:30s}")
    public void recover() {
        try {
            int resumed = recoverOnce();
            if (resumed > 0) {
                log.info("Recovery resumed {} file(s)", resumed);
            }
        } catch (RuntimeException e) {
            log.warn("Recovery cycle skipped: {}", e.getMessage());
        }
    }

    /** One recovery pass; returns the number of files handed back to ingestion. */
    public int recoverOnce() {
        Map<StorageType, StorageState> states = storageStates.currentStates();
        Instant staleBefore = Instant.now().minus(properties.staleAfter());
        List<FileRecord> candidates = new ArrayList<>();
        for (FileRecord file : files.findTop100ByStatusInOrderByReceivedAtAsc(
                EnumSet.of(FileStatus.PENDING, FileStatus.RECEIVED, FileStatus.ANALYZING, FileStatus.CATEGORIZED))) {
            if (file.getStatus() == FileStatus.PENDING) {
                if (anyStorageUp(file, states)) {
                    candidates.add(file);
                }
            } else if (INTERRUPTED.contains(file.getStatus()) && file.getReceivedAt().isBefore(staleBefore)) {
                candidates.add(file);
            }
        }
        int resumed = 0;
        for (FileRecord file : candidates) {
            try {
                ingestion.resume(file);
                resumed++;
            } catch (Exception e) {
                log.warn("File {} could not be resumed yet: {}", file.getId(), e.getMessage());
            }
        }
        return resumed;
    }

    private boolean anyStorageUp(FileRecord file, Map<StorageType, StorageState> states) {
        List<StorageType> storages = new ArrayList<>();
        policies.activeIfPresent().map(active -> active.policy().category(file.getCategory()))
                .ifPresent((Policy.CategoryPolicy rules) -> {
                    storages.add(rules.primary());
                    storages.addAll(rules.fallbacks());
                });
        if (storages.isEmpty()) {
            storages.addAll(List.of(StorageType.values()));
        }
        return storages.stream().anyMatch(s -> states.getOrDefault(s, StorageState.unknown()).isUp());
    }
}
