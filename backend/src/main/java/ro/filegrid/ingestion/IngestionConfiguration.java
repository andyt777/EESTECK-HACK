package ro.filegrid.ingestion;

import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Ingestion runs off the request thread: the last tus PATCH returns as soon as
 * the bytes are on disk, and hashing a large file never blocks an HTTP worker.
 */
@Configuration
@Profile("api")
@EnableAsync
@EnableConfigurationProperties(IngestionConfiguration.IngestionProperties.class)
public class IngestionConfiguration {

    public static final String EXECUTOR = "ingestionExecutor";

    @Bean(name = EXECUTOR)
    public Executor ingestionExecutor(IngestionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ingest-");
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(1000);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * @param threads          files ingested at the same time (hash + classification + routing)
     * @param recoveryInterval how often PENDING and interrupted ingestions are re-examined
     * @param staleAfter       an ingestion left in RECEIVED / ANALYZING / CATEGORIZED for longer is resumed
     */
    @ConfigurationProperties("filegrid.ingestion")
    public record IngestionProperties(@DefaultValue("2") int threads,
                                      @DefaultValue("30s") java.time.Duration recoveryInterval,
                                      @DefaultValue("2m") java.time.Duration staleAfter) {
    }
}
