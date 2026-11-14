package ro.filegrid.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class StagingPropertiesTest {

    @Test
    void defaultValuesArePopulated() {
        StagingProperties props = new StagingProperties(null, 0, null, null);

        assertThat(props.directory()).isEqualTo("/data/staging");
        assertThat(props.maxSizeBytes()).isEqualTo(5368709120L);
        assertThat(props.cleanupOlderThan()).isEqualTo(Duration.ofHours(24));
        assertThat(props.cleanupInterval()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void resolvesExistingWritableDirectory(@TempDir Path tempDir) {
        StagingProperties props = new StagingProperties(tempDir.toString(), 1024, Duration.ofHours(12), Duration.ofMinutes(30));

        Path resolved = props.resolvedDirectory();
        assertThat(resolved).isEqualTo(tempDir.toAbsolutePath().normalize());
        assertThat(Files.isDirectory(resolved)).isTrue();
    }

    @Test
    void resolvesTusDirectoryUnderResolvedDirectory(@TempDir Path tempDir) {
        StagingProperties props = new StagingProperties(tempDir.toString(), 1024, Duration.ofHours(12), Duration.ofMinutes(30));

        Path tusDir = props.tusDirectory();
        assertThat(tusDir).isEqualTo(tempDir.resolve("tus").toAbsolutePath().normalize());
        assertThat(Files.isDirectory(tusDir)).isTrue();
    }

    @Test
    void fallsBackToTempDirWhenConfiguredDirIsInvalid() {
        // Pointing to a file rather than a directory so directory creation fails or unwritable
        StagingProperties props = new StagingProperties("Z:\\nonexistent_drive_12345\\impossible", 1024, null, null);

        Path resolved = props.resolvedDirectory();
        assertThat(resolved).isNotNull();
        assertThat(resolved.toString()).contains("filegrid-staging");
        assertThat(Files.isDirectory(resolved)).isTrue();
    }
}
