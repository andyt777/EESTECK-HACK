package ro.filegrid.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.desair.tus.server.TusFileUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing the {@link TusFileUploadService} bean configured
 * for resumable uploads streaming directly to the staging disk volume.
 */
@Configuration
@EnableConfigurationProperties({StagingProperties.class, UploadProperties.class})
public class TusUploadConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TusUploadConfiguration.class);

    @Bean(destroyMethod = "cleanup")
    public TusFileUploadService tusFileUploadService(
            StagingProperties stagingProperties,
            UploadProperties uploadProperties) throws IOException {

        Path tusPath = stagingProperties.tusDirectory();
        Files.createDirectories(tusPath);

        log.info("Configuring TusFileUploadService: storagePath={}, uploadUri=/api/uploads, maxSize={}, expiration={}",
                tusPath.toAbsolutePath(), uploadProperties.maxSizeBytes(), stagingProperties.cleanupOlderThan());

        return new TusFileUploadService()
                .withStoragePath(tusPath.toAbsolutePath().toString())
                .withUploadUri("/api/uploads")
                .withMaxUploadSize(uploadProperties.maxSizeBytes())
                .withUploadExpirationPeriod(stagingProperties.cleanupOlderThan().toMillis())
                .withThreadLocalCache(true)
                .withUploadDeduplication(true);
    }
}
