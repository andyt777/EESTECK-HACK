package ro.filegrid.meter;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.filegrid.storage.database.StoreDbConfig;

/**
 * Reconciliation of meter ingestion, every minute (api role only, so each
 * number is published by exactly one process):
 * <ul>
 *   <li>gaps: per meter, readings missing between the lowest and highest
 *       {@code seq} received in the last 24 h;</li>
 *   <li>duplicates rejected: total of ingestion attempts refused because the
 *       reading was already stored (api batch check + worker ON CONFLICT);</li>
 *   <li>duplicates written: readings actually stored more than once, counted
 *       independently in store-db and meta-db. Must always be 0.</li>
 * </ul>
 * Published as {@code filegrid_meter_gaps_total},
 * {@code filegrid_meter_duplicates_rejected_total} and
 * {@code filegrid_meter_duplicates_written_total}.
 */
@Component
@Profile("api")
public class MeterReconciliation {

    private static final Logger log = LoggerFactory.getLogger(MeterReconciliation.class);

    private final JdbcTemplate storeDb;
    private final JdbcTemplate metaDb;
    private final MeterCounters counters;
    private volatile Snapshot last = Snapshot.EMPTY;

    /** Result of the last reconciliation. */
    public record Snapshot(long gaps, long duplicatesRejected, long duplicatesWritten, long readingsStored,
                           long meters, Instant at) {
        static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, null);
    }

    public MeterReconciliation(@Qualifier(StoreDbConfig.QUALIFIER) JdbcTemplate storeDb, JdbcTemplate metaDb,
                               MeterCounters counters, MeterRegistry registry) {
        this.storeDb = storeDb;
        this.metaDb = metaDb;
        this.counters = counters;
        FunctionCounter.builder("filegrid.meter.gaps", this, r -> r.last.gaps())
                .description("Meter readings missing between the first and last seq of each meter (last 24 h)")
                .register(registry);
        FunctionCounter.builder("filegrid.meter.duplicates.rejected", this, r -> r.last.duplicatesRejected())
                .description("Readings refused because they were already stored")
                .register(registry);
        FunctionCounter.builder("filegrid.meter.duplicates.written", this, r -> r.last.duplicatesWritten())
                .description("Readings stored more than once (must always be 0)")
                .register(registry);
        FunctionCounter.builder("filegrid.meter.readings.stored", this, r -> r.last.readingsStored())
                .description("Readings stored and verified in the Database storage")
                .register(registry);
        Gauge.builder("filegrid.meter.meters.active", this, r -> r.last.meters())
                .description("Meters that sent at least one reading in the last 24 h")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${filegrid.meter.reconciliation-interval:60s}", initialDelay = 5_000)
    public void reconcile() {
        try {
            last = reconcileOnce();
            if (last.duplicatesWritten() > 0) {
                log.error("Meter reconciliation found {} reading(s) stored more than once", last.duplicatesWritten());
            }
        } catch (RuntimeException e) {
            log.warn("Meter reconciliation skipped: {}", e.getMessage());
        }
    }

    public Snapshot reconcileOnce() {
        Long gaps = storeDb.queryForObject("""
                SELECT coalesce(sum(expected - received), 0) FROM (
                    SELECT max(seq) - min(seq) + 1 AS expected, count(*) AS received
                      FROM meter_reading_blob
                     WHERE created_at > now() - interval '24 hours'
                     GROUP BY meter_id) per_meter
                """, Long.class);
        Long meters = storeDb.queryForObject("""
                SELECT count(DISTINCT meter_id) FROM meter_reading_blob WHERE created_at > now() - interval '24 hours'
                """, Long.class);
        // Independent of the constraints that should make this impossible: count, do not assume.
        Long storedTwice = storeDb.queryForObject("""
                SELECT count(*) - count(DISTINCT (meter_id, period_start)) FROM meter_reading_blob
                """, Long.class);
        Long recordedTwice = metaDb.queryForObject("""
                SELECT coalesce(sum(n - 1), 0) FROM (
                    SELECT count(*) AS n FROM file_record
                     WHERE category = 'METER_READING' AND idempotency_key IS NOT NULL
                     GROUP BY idempotency_key HAVING count(*) > 1) twice
                """, Long.class);
        long written = nz(storedTwice) + nz(recordedTwice);
        counters.set(MeterCounters.DUPLICATES_WRITTEN, written);
        Map<String, Long> totals = counters.all();
        return new Snapshot(Math.max(0, nz(gaps)), totals.getOrDefault(MeterCounters.DUPLICATES_REJECTED, 0L), written,
                totals.getOrDefault(MeterCounters.READINGS_STORED, 0L), nz(meters), Instant.now());
    }

    public Snapshot last() {
        return last;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
