package ro.filegrid.storage.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageHealthResult;
import ro.filegrid.storage.StorageType;

/**
 * Probes every storage on a fixed interval (default 10 s), records the result
 * in {@code storage_health} (meta-db) and publishes {@code filegrid_storage_up{storage}}.
 *
 * <p>Runs only in the api role, so that one process owns the table instead of
 * every worker probing the same storages.
 */
@Component
@Profile("api")
public class StorageHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(StorageHealthMonitor.class);
    private static final String UPSERT = """
            INSERT INTO storage_health (storage, status, last_check, latency_ms, detail)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (storage) DO UPDATE
                SET status = EXCLUDED.status, last_check = EXCLUDED.last_check,
                    latency_ms = EXCLUDED.latency_ms, detail = EXCLUDED.detail
            """;

    private final List<StorageAdapter> adapters;
    private final JdbcTemplate metaDb;
    private final Map<StorageType, AtomicInteger> upGauges = new EnumMap<>(StorageType.class);
    private final Map<StorageType, StorageHealthResult> lastResults = new EnumMap<>(StorageType.class);

    public StorageHealthMonitor(List<StorageAdapter> adapters, JdbcTemplate metaDb, MeterRegistry registry) {
        this.adapters = adapters;
        this.metaDb = metaDb;
        for (StorageAdapter adapter : adapters) {
            AtomicInteger value = new AtomicInteger(0);
            upGauges.put(adapter.type(), value);
            Gauge.builder("filegrid_storage_up", value, AtomicInteger::get)
                    .description("1 when the storage answered the last health probe, 0 otherwise")
                    .tag("storage", adapter.type().name())
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${filegrid.storage.health-interval}", initialDelay = 0)
    public void checkAll() {
        for (StorageAdapter adapter : adapters) {
            StorageHealthResult result = adapter.health();
            record(adapter.type(), result);
        }
    }

    /** Last probe result per storage (empty until the first probe ran). */
    public synchronized Map<StorageType, StorageHealthResult> lastResults() {
        return Map.copyOf(lastResults);
    }

    private void record(StorageType type, StorageHealthResult result) {
        StorageHealthResult previous;
        synchronized (this) {
            previous = lastResults.put(type, result);
        }
        upGauges.get(type).set(result.up() ? 1 : 0);
        if (previous == null || previous.up() != result.up()) {
            if (result.up()) {
                log.info("Storage {} is UP ({} ms)", type, result.latencyMs());
            } else {
                log.warn("Storage {} is DOWN: {}", type, result.detail());
            }
        }
        try {
            metaDb.update(UPSERT, type.name(), result.up() ? "UP" : "DOWN",
                    Timestamp.from(Instant.now()), result.latencyMs(), truncate(result.detail()));
        } catch (RuntimeException e) {
            log.warn("Could not save the health of storage {}: {}", type, e.getMessage());
        }
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 500 ? text : text.substring(0, 500);
    }
}
