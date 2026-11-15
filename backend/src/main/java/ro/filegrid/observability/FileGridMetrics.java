package ro.filegrid.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;
import ro.filegrid.file.FileCategory;
import ro.filegrid.storage.StorageType;

/**
 * Business metrics of the storage pipeline (Stage 8). Every metric carries the
 * tags {@code category} and {@code storage}; operations also carry {@code outcome}.
 *
 * <p>Prometheus names: {@code filegrid_store_duration_seconds},
 * {@code filegrid_retrieve_duration_seconds} (percentile histograms, so p50/p95/p99
 * come from {@code histogram_quantile}), {@code filegrid_operations_total},
 * {@code filegrid_bytes_total}, {@code filegrid_fallback_total}.
 */
@Component
public class FileGridMetrics {

    public static final String STORE = "store";
    public static final String RETRIEVE = "retrieve";

    /** Outcomes of one store attempt / one retrieval. */
    public static final String VERIFIED = "verified";
    public static final String MISMATCH = "mismatch";
    public static final String ERROR = "error";
    public static final String INELIGIBLE = "ineligible";
    public static final String SUCCESS = "success";

    private static final Duration MIN_EXPECTED = Duration.ofMillis(1);
    private static final Duration MAX_EXPECTED = Duration.ofMinutes(30);

    private final MeterRegistry registry;

    public FileGridMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One attempt to write a file to a storage, including its read-back verification. */
    public void recordStore(FileCategory category, StorageType storage, String outcome, long nanos, long bytes) {
        record(STORE, "filegrid.store.duration", "Write + read-back verification of one file on a storage",
                category, storage, outcome, nanos, bytes);
    }

    /** One read of a stored file (download, preview, on-demand verification, scrubber). */
    public void recordRetrieve(FileCategory category, StorageType storage, String outcome, long nanos, long bytes) {
        record(RETRIEVE, "filegrid.retrieve.duration", "Read of one stored file from a storage",
                category, storage, outcome, nanos, bytes);
    }

    /** Agent 01 moved a transfer from one storage to the next one of the policy. */
    public void recordFallback(FileCategory category, StorageType from, StorageType to) {
        Counter.builder("filegrid.fallback")
                .description("Transfers that fell back from one storage to another")
                .tag("category", category.name())
                .tag("storage", from.name())
                .tag("to", to.name())
                .register(registry)
                .increment();
    }

    private void record(String operation, String timerName, String description, FileCategory category,
                        StorageType storage, String outcome, long nanos, long bytes) {
        String cat = category == null ? "UNKNOWN" : category.name();
        String sto = storage == null ? "UNKNOWN" : storage.name();
        Timer.builder(timerName)
                .description(description)
                .tag("category", cat)
                .tag("storage", sto)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(MIN_EXPECTED)
                .maximumExpectedValue(MAX_EXPECTED)
                .register(registry)
                .record(Duration.ofNanos(Math.max(0, nanos)));
        Counter.builder("filegrid.operations")
                .description("Store / retrieve operations by outcome")
                .tag("operation", operation)
                .tag("category", cat)
                .tag("storage", sto)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        if (bytes > 0) {
            Counter.builder("filegrid.bytes")
                    .description("Bytes written to (store) or read from (retrieve) the storages")
                    .baseUnit("bytes")
                    .tag("operation", operation)
                    .tag("category", cat)
                    .tag("storage", sto)
                    .register(registry)
                    .increment(bytes);
        }
    }
}
