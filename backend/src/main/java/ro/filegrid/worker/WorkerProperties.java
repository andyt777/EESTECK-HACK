package ro.filegrid.worker;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import ro.filegrid.file.FileCategory;

/**
 * Worker settings ({@code filegrid.worker.*}).
 *
 * @param categories  comma-separated categories whose queues this worker consumes
 *                    (from {@code WORKER_CATEGORIES}, for example {@code METER_READING,FAULT_RECORD})
 * @param concurrency how many transfers one worker instance handles at the same time, per category
 *                    queue (from {@code WORKER_CONCURRENCY}); each transfer holds its own buffers,
 *                    so this bounds the worker's memory
 * @param prefetch    unacknowledged jobs a consumer may hold (from {@code WORKER_PREFETCH}, default 1:
 *                    a long video never keeps other jobs waiting behind it on the same consumer)
 * @param perCategory optional overrides of concurrency / prefetch for one category
 *                    ({@code filegrid.worker.per-category.DRONE_VIDEO.concurrency=1})
 * @param meterBatchSize meter readings written per store-db transaction (default 200)
 */
@ConfigurationProperties("filegrid.worker")
public record WorkerProperties(String categories, int concurrency, Integer prefetch,
                               Map<FileCategory, CategorySettings> perCategory, Integer meterBatchSize) {

    @ConstructorBinding
    public WorkerProperties {
        perCategory = perCategory == null ? Map.of() : Map.copyOf(perCategory);
        meterBatchSize = meterBatchSize == null || meterBatchSize < 1 ? 200 : meterBatchSize;
    }

    public WorkerProperties(String categories, int concurrency) {
        this(categories, concurrency, null, null, null);
    }

    /** Overrides for one category; a {@code null} value keeps the worker-wide setting. */
    public record CategorySettings(Integer concurrency, Integer prefetch) {
    }

    public int concurrencyFor(FileCategory category) {
        CategorySettings settings = perCategory.get(category);
        return settings != null && settings.concurrency() != null ? settings.concurrency() : concurrency;
    }

    public int prefetchFor(FileCategory category) {
        CategorySettings settings = perCategory.get(category);
        if (settings != null && settings.prefetch() != null) {
            return settings.prefetch();
        }
        return prefetch == null ? 1 : prefetch;
    }

    /**
     * Parses {@link #categories()}.
     *
     * @throws IllegalArgumentException for an unknown category name
     */
    public Set<FileCategory> parsedCategories() {
        if (categories == null || categories.isBlank()) {
            return EnumSet.noneOf(FileCategory.class);
        }
        return Arrays.stream(categories.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(WorkerProperties::parse)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FileCategory.class)));
    }

    private static FileCategory parse(String name) {
        try {
            return FileCategory.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown category in WORKER_CATEGORIES: '" + name
                    + "'. Allowed: " + Arrays.toString(FileCategory.values()), e);
        }
    }
}
