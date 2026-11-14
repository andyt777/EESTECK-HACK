package ro.filegrid.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the shared staging volume ({@code filegrid.staging.*}).
 *
 * @param directory        directory where incoming chunks and files are staged (default {@code /data/staging})
 * @param maxSizeBytes     maximum allowed upload size in bytes (default 5 GB)
 * @param cleanupOlderThan age after which partial or abandoned uploads are purged (default 24h)
 * @param cleanupInterval  how often the cleanup scheduler runs (default 1h)
 */
@ConfigurationProperties("filegrid.staging")
public record StagingProperties(
        @DefaultValue("/data/staging") String directory,
        @DefaultValue("5368709120") long maxSizeBytes,
        @DefaultValue("24h") Duration cleanupOlderThan,
        @DefaultValue("1h") Duration cleanupInterval
) {
    private static final Logger log = LoggerFactory.getLogger(StagingProperties.class);

    public StagingProperties {
        if (directory == null || directory.isBlank()) {
            directory = "/data/staging";
        }
        if (maxSizeBytes <= 0) {
            maxSizeBytes = 5368709120L;
        }
        if (cleanupOlderThan == null) {
            cleanupOlderThan = Duration.ofHours(24);
        }
        if (cleanupInterval == null) {
            cleanupInterval = Duration.ofHours(1);
        }
    }

    /**
     * Resolves the active staging directory, falling back to {@code <java.io.tmpdir>/filegrid-staging}
     * if the configured path cannot be created or is not writable.
     */
    public Path resolvedDirectory() {
        if (directory != null && !directory.isBlank()) {
            try {
                Path dir = Path.of(directory).toAbsolutePath().normalize();
                Files.createDirectories(dir);
                if (Files.isWritable(dir)) {
                    return dir;
                }
                log.warn("Staging directory '{}' is not writable; falling back to temporary directory", directory);
            } catch (Exception e) {
                log.warn("Failed to create staging directory '{}' ({}): falling back to temporary directory",
                        directory, e.getMessage());
            }
        }

        Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "filegrid-staging").toAbsolutePath().normalize();
        try {
            Files.createDirectories(fallback);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create fallback staging directory: " + fallback, e);
        }
        return fallback;
    }

    /**
     * Resolves the directory dedicated for active Tus chunk uploads and locks.
     */
    public Path tusDirectory() {
        Path tusDir = resolvedDirectory().resolve("tus");
        try {
            Files.createDirectories(tusDir);
        } catch (IOException e) {
            log.warn("Failed to create tus subfolder '{}': {}", tusDir, e.getMessage());
        }
        return tusDir;
    }
}
