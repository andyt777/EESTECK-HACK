package ro.filegrid.policy;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the policy file is and how often it is re-checked ({@code filegrid.policy.*}).
 *
 * @param file          path of {@code policies.yaml} (mounted, never inside the jar)
 * @param checkInterval how often the file checksum and the latest stored version are compared
 */
@ConfigurationProperties("filegrid.policy")
public record PolicyProperties(String file, Duration checkInterval) {

    public Path filePath() {
        return Path.of(file);
    }
}
