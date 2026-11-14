package ro.filegrid.policy;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.filegrid.policy.PolicyService.InvalidPolicyException;
import ro.filegrid.policy.PolicyService.PolicySource;
import ro.filegrid.policy.PolicyService.SubmitResult;
import ro.filegrid.text.Texts;

/**
 * Loads {@code config/policies.yaml} at startup and again whenever it changes,
 * without a restart. A change is noticed in two independent ways:
 * <ul>
 *   <li>a {@link WatchService} on the directory, which reacts at once where the
 *       file system delivers notifications;</li>
 *   <li>a periodic comparison of the file's SHA-256 (default every 10 s), which
 *       also works on Windows bind mounts, where notifications never reach the
 *       container.</li>
 * </ul>
 * Runs only in the api role, so that one process turns file changes into versions.
 */
@Component
@Profile("api")
public class PolicyFileWatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicyFileWatcher.class);
    private static final String FILE_AUTHOR = "policies.yaml";
    /** Editors write in several steps; wait for them to finish before reading. */
    private static final long SETTLE_MILLIS = 250;

    private final PolicyService policies;
    private final Texts texts;
    private final Path file;

    private String lastSeenSha256;
    private volatile FileStatus status;
    private WatchService watchService;
    private Thread watchThread;

    public PolicyFileWatcher(PolicyService policies, PolicyProperties properties, Texts texts) {
        this.policies = policies;
        this.texts = texts;
        this.file = properties.filePath().toAbsolutePath().normalize();
        this.status = new FileStatus(file.toString(), null, null, List.of());
    }

    /** Startup: the application refuses to start without any usable policy. */
    @Override
    public void run(ApplicationArguments args) {
        checkFile();
        if (policies.activeIfPresent().isEmpty()) {
            throw new IllegalStateException("No usable routing policy: " + file
                    + " is missing or invalid and no earlier version exists. Problems: " + status.lastErrors());
        }
        startWatching();
    }

    /** Re-reads the file if its content changed since the last look. Safe to call at any time. */
    @Scheduled(fixedDelayString = "${filegrid.policy.check-interval}",
            initialDelayString = "${filegrid.policy.check-interval}")
    public synchronized void checkFile() {
        String content;
        try {
            if (Files.size(file) > PolicyParser.MAX_CONTENT_CHARS * 4L) {
                throw new IOException("the file is too large");
            }
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (!"unreadable".equals(lastSeenSha256)) {
                log.warn("Cannot read the policy file {}: {}. The active policy is kept.", file, e.toString());
                lastSeenSha256 = "unreadable";
            }
            status = new FileStatus(file.toString(), Instant.now(), null,
                    List.of(texts.get("policy.error.unreadable", String.valueOf(e.getMessage()))));
            return;
        }
        String sha256 = PolicyService.sha256(content);
        if (sha256.equals(lastSeenSha256)) {
            return;
        }
        // Remember the checksum first: an invalid file is reported once, not on every check.
        lastSeenSha256 = sha256;
        try {
            if (policies.lastFileSha256().filter(sha256::equals).isPresent()) {
                // Same text as the last one accepted from the file (typically after a
                // restart): a newer version saved from the admin API stays in force.
                policies.parseAndValidate(content);
                status = new FileStatus(file.toString(), Instant.now(), sha256, List.of());
                return;
            }
            SubmitResult result = policies.submit(content, PolicySource.FILE, FILE_AUTHOR);
            status = new FileStatus(file.toString(), Instant.now(), sha256, List.of());
            if (result.changed()) {
                log.info("Policy file {} accepted as {}", file, result.policy().label());
            }
        } catch (InvalidPolicyException e) {
            status = new FileStatus(file.toString(), Instant.now(), sha256, e.errors());
            log.error("Policy file {} was REFUSED; the previous policy stays active. Problems: {}",
                    file, e.errors());
        } catch (RuntimeException e) {
            // For example the database is unreachable: try again on the next check.
            lastSeenSha256 = null;
            log.warn("Could not store the policy read from {}: {}", file, e.toString());
        }
    }

    /** What happened the last time the file was read (shown by GET /api/policies). */
    public FileStatus status() {
        return status;
    }

    private void startWatching() {
        Path directory = file.getParent();
        try {
            watchService = directory.getFileSystem().newWatchService();
            directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException | RuntimeException e) {
            log.warn("File notifications are not available for {} ({}); relying on the periodic check only",
                    directory, e.toString());
            return;
        }
        watchThread = new Thread(this::watchLoop, "policy-file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void watchLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                boolean touched = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object context = event.context();
                    touched |= context instanceof Path changed && changed.getFileName().equals(file.getFileName());
                }
                key.reset();
                if (touched) {
                    Thread.sleep(SETTLE_MILLIS);
                    checkFile();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // shutting down
        } catch (RuntimeException e) {
            log.warn("The policy file watcher stopped ({}); the periodic check continues", e.toString());
        }
    }

    @PreDestroy
    void stop() {
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                // nothing to do at shutdown
            }
        }
    }

    /**
     * @param lastCheckedAt when the content was last read ({@code null} before the first read)
     * @param sha256        checksum of the content read
     * @param lastErrors    why the file was refused; empty when it was accepted
     */
    public record FileStatus(String path, Instant lastCheckedAt, String sha256, List<String> lastErrors) {
    }
}
