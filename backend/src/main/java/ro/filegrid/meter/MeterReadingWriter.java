package ro.filegrid.meter;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.observability.FileGridMetrics;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.database.StoreDbConfig;

/**
 * worker-meter: writes a batch of readings to the Database storage in one
 * transaction, without ever writing a reading twice.
 *
 * <ol>
 *   <li>store-db: {@code INSERT ... ON CONFLICT DO NOTHING} on the primary key
 *       readingId (and on meterId + periodStart); the rows actually inserted come
 *       back with RETURNING, the others are duplicates;</li>
 *   <li>still in that transaction, the stored bytes are hashed by PostgreSQL and
 *       compared with the SHA-256 computed by the api: commit only if all match;</li>
 *   <li>meta-db: a file_record per reading (idempotency_key = readingId, again
 *       ON CONFLICT DO NOTHING), then the totals.</li>
 * </ol>
 * The caller acknowledges the messages only after this returns. A crash at any
 * point means redelivery, which is harmless: every step is idempotent.
 */
@Component
public class MeterReadingWriter {

    private static final Logger log = LoggerFactory.getLogger(MeterReadingWriter.class);
    static final String ACTOR = "agent-01";

    private final DataSource storeDb;
    private final JdbcTemplate metaDb;
    private final MeterCounters counters;
    private final FileGridMetrics metrics;

    public MeterReadingWriter(@Qualifier(StoreDbConfig.QUALIFIER) DataSource storeDb, JdbcTemplate metaDb,
                              MeterCounters counters, FileGridMetrics metrics) {
        this.metrics = metrics;
        this.storeDb = storeDb;
        this.metaDb = metaDb;
        this.counters = counters;
    }

    /** @param stored readings inserted by this batch; {@code duplicates} were already there */
    public record BatchOutcome(int stored, int duplicates) {
    }

    public BatchOutcome write(List<MeterReadingMessage> readings) {
        if (readings.isEmpty()) {
            return new BatchOutcome(0, 0);
        }
        long started = System.nanoTime();
        Set<String> inserted;
        try {
            inserted = insertIntoStore(readings);
        } catch (RuntimeException e) {
            metrics.recordStore(FileCategory.METER_READING, StorageType.DATABASE, FileGridMetrics.ERROR,
                    System.nanoTime() - started, 0);
            throw e;
        }
        // One observation per batch (~200 readings) written and verified in one transaction.
        metrics.recordStore(FileCategory.METER_READING, StorageType.DATABASE, FileGridMetrics.VERIFIED,
                System.nanoTime() - started, readings.stream().mapToLong(r -> r.content().length).sum());
        recordInMetaDb(readings, inserted);
        int duplicates = readings.size() - inserted.size();
        counters.add(MeterCounters.READINGS_STORED, inserted.size());
        counters.add(MeterCounters.DUPLICATES_REJECTED, duplicates);
        if (duplicates > 0) {
            log.info("Meter batch: {} stored, {} duplicate(s) rejected", inserted.size(), duplicates);
        }
        return new BatchOutcome(inserted.size(), duplicates);
    }

    private Set<String> insertIntoStore(List<MeterReadingMessage> readings) {
        int n = readings.size();
        String[] ids = new String[n];
        String[] meters = new String[n];
        Timestamp[] starts = new Timestamp[n];
        Timestamp[] ends = new Timestamp[n];
        Long[] seqs = new Long[n];
        String[] regions = new String[n];
        String[] zones = new String[n];
        String[] stations = new String[n];
        String[] transformers = new String[n];
        String[] origins = new String[n];
        byte[][] contents = new byte[n][];
        Integer[] sizes = new Integer[n];
        String[] hashes = new String[n];
        String[] batches = new String[n];
        for (int i = 0; i < n; i++) {
            MeterReadingMessage r = readings.get(i);
            ids[i] = r.readingId();
            meters[i] = r.meterId();
            starts[i] = Timestamp.from(r.periodStart());
            ends[i] = Timestamp.from(r.periodEnd());
            seqs[i] = r.seq();
            regions[i] = r.region();
            zones[i] = r.zone();
            stations[i] = r.station();
            transformers[i] = r.transformer();
            origins[i] = (r.dataOrigin() == null ? DataOrigin.REAL : r.dataOrigin()).name();
            contents[i] = r.content();
            sizes[i] = r.content().length;
            hashes[i] = r.sha256();
            batches[i] = r.batchKey();
        }
        try (Connection connection = storeDb.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Set<String> inserted = new HashSet<>();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO meter_reading_blob (reading_id, meter_id, period_start, period_end, seq, region,
                                                        zone, station, transformer, data_origin, content, size_bytes,
                                                        sha256, batch_key)
                        SELECT * FROM unnest(?::varchar[], ?::varchar[], ?::timestamptz[], ?::timestamptz[],
                                             ?::bigint[], ?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[],
                                             ?::varchar[], ?::bytea[], ?::int[], ?::varchar[], ?::varchar[])
                        ON CONFLICT DO NOTHING
                        RETURNING reading_id
                        """)) {
                    Array[] arrays = {
                            connection.createArrayOf("varchar", ids), connection.createArrayOf("varchar", meters),
                            connection.createArrayOf("timestamptz", starts), connection.createArrayOf("timestamptz", ends),
                            connection.createArrayOf("bigint", seqs), connection.createArrayOf("varchar", regions),
                            connection.createArrayOf("varchar", zones), connection.createArrayOf("varchar", stations),
                            connection.createArrayOf("varchar", transformers), connection.createArrayOf("varchar", origins),
                            connection.createArrayOf("bytea", contents), connection.createArrayOf("int", sizes),
                            connection.createArrayOf("varchar", hashes), connection.createArrayOf("varchar", batches)};
                    for (int i = 0; i < arrays.length; i++) {
                        insert.setArray(i + 1, arrays[i]);
                    }
                    try (ResultSet rs = insert.executeQuery()) {
                        while (rs.next()) {
                            inserted.add(rs.getString(1));
                        }
                    }
                }
                verifyStoredHashes(connection, inserted);
                connection.commit();
                return inserted;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException("Meter readings could not be written to store-db: " + e.getMessage(), e);
        }
    }

    /** Read-back inside the transaction: PostgreSQL hashes what it actually stored. */
    private static void verifyStoredHashes(Connection connection, Set<String> inserted) throws SQLException {
        if (inserted.isEmpty()) {
            return;
        }
        try (PreparedStatement check = connection.prepareStatement("""
                SELECT reading_id FROM meter_reading_blob
                 WHERE reading_id = ANY (?) AND encode(sha256(content), 'hex') <> sha256
                """)) {
            check.setArray(1, connection.createArrayOf("varchar", inserted.toArray(String[]::new)));
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    throw new StorageException("Read-back of meter reading " + rs.getString(1)
                            + " does not match its SHA-256; the batch is rolled back");
                }
            }
        }
    }

    private void recordInMetaDb(List<MeterReadingMessage> readings, Set<String> insertedNow) {
        Instant now = Instant.now();
        List<Object[]> records = new ArrayList<>(readings.size());
        for (MeterReadingMessage r : readings) {
            DataOrigin origin = r.dataOrigin() == null ? DataOrigin.REAL : r.dataOrigin();
            records.add(new Object[] {
                    MeterReadings.fileId(r.readingId()),
                    "reading-" + r.meterId() + "-" + r.periodStart() + ".json",
                    (long) r.content().length,
                    r.sha256(), r.sha256(),
                    Timestamp.from(now),
                    MeterReadings.storageKey(r.readingId(), r.periodStart()),
                    origin.name(),
                    origin == DataOrigin.SIMULATED ? "SIMULATOR" : "API",
                    r.actor() == null ? "unknown" : r.actor(),
                    Timestamp.from(r.receivedAt() == null ? now : r.receivedAt()),
                    Timestamp.from(now),
                    r.readingId(),
                    traceId(r.batchKey())});
        }
        // Every reading of the batch, not only the ones inserted now: after a crash between the
        // two databases, the redelivered batch completes the metadata that is missing.
        List<UUID> created = new ArrayList<>();
        metaDb.execute((java.sql.Connection connection) -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO file_record (id, category, original_name, content_type, size_bytes, sha256_plain,
                                             sha256_stored, status, integrity_status, last_verified_at, storage_type,
                                             storage_key, encrypted, sensitivity, data_origin, source, uploaded_by,
                                             received_at, stored_at, idempotency_key, trace_id)
                    VALUES (?, 'METER_READING', ?, 'application/json', ?, ?, ?, 'STORED_VERIFIED', 'VERIFIED_INTACT',
                            ?, 'DATABASE', ?, FALSE, 'NONE', ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING id
                    """)) {
                for (Object[] row : records) {
                    for (int i = 0; i < row.length; i++) {
                        insert.setObject(i + 1, row[i]);
                    }
                    try (ResultSet rs = insert.executeQuery()) {
                        if (rs.next()) {
                            created.add(rs.getObject(1, UUID.class));
                        }
                    }
                }
            }
            return null;
        });
        if (created.isEmpty()) {
            return;
        }
        Set<UUID> createdIds = new HashSet<>(created);
        List<Object[]> contexts = new ArrayList<>();
        List<Object[]> events = new ArrayList<>();
        for (MeterReadingMessage r : readings) {
            UUID fileId = MeterReadings.fileId(r.readingId());
            if (!createdIds.contains(fileId)) {
                continue;
            }
            contexts.add(new Object[] {fileId, r.zone(), r.station(), r.transformer(), r.meterId(),
                    Timestamp.from(r.periodStart())});
            events.add(new Object[] {fileId, ACTOR,
                    "{\"storage\":\"DATABASE\",\"result\":\"VERIFIED_INTACT\",\"sha256\":\"" + r.sha256()
                            + "\",\"batch\":\"" + escape(r.batchKey()) + "\",\"stored\":"
                            + insertedNow.contains(r.readingId()) + "}",
                    traceId(r.batchKey()), Timestamp.from(now)});
        }
        metaDb.batchUpdate("""
                INSERT INTO file_context (file_id, zone, station, equipment, meter_id, event_time)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, contexts);
        metaDb.batchUpdate("""
                INSERT INTO file_event (file_id, type, actor, details, trace_id, at)
                VALUES (?, 'VERIFIED', ?, ?::jsonb, ?, ?)
                """, events);
    }

    /** trace_id of the readings of one batch (the batch key itself may be longer than the column). */
    static String traceId(String batchKey) {
        return batchKey == null ? null : MeterReadings.sha256(
                batchKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 32);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
