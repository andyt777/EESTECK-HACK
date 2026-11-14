package ro.filegrid.worker;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ro.filegrid.file.FileCategory;

/**
 * Worker settings ({@code filegrid.worker.*}).
 *
 * @param categories  comma-separated categories whose queues this worker consumes
 *                    (from {@code WORKER_CATEGORIES}, for example {@code METER_READING,FAULT_RECORD})
 * @param concurrency how many transfers one worker instance handles at the same time
 *                    (from {@code WORKER_CONCURRENCY}); each transfer holds its own buffers,
 *                    so this bounds the worker's memory
 */
@ConfigurationProperties("filegrid.worker")
public record WorkerProperties(String categories, int concurrency) {

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
