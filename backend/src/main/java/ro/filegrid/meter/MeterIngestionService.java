package ro.filegrid.meter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.storage.Sha256;
import ro.filegrid.text.Texts;

/**
 * {@code POST /api/meters/readings}: validates a batch, gives every reading
 * its id, sorts out the readings that are already stored (duplicates), queues
 * the others and answers only once RabbitMQ confirmed all of them.
 *
 * <p>The Idempotency-Key makes a retried batch harmless: the stored answer of
 * the first attempt is returned and nothing is queued again. Even without it,
 * a reading can never be written twice (store-db primary key + ON CONFLICT DO
 * NOTHING in worker-meter).
 */
@Service
@Profile("api")
public class MeterIngestionService {

    public static final int MAX_BATCH = 500;
    /** Largest single reading (the requirement says 1-50 KB). */
    public static final int MAX_READING_BYTES = 64 * 1024;
    /** Largest batch held in memory, in line with the 16 MB buffer rule. */
    public static final int MAX_BATCH_BYTES = 16 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(MeterIngestionService.class);

    private final JdbcTemplate metaDb;
    private final MeterReadingPublisher publisher;
    private final MeterCounters counters;
    private final ObjectMapper json;
    private final Texts texts;

    public MeterIngestionService(JdbcTemplate metaDb, MeterReadingPublisher publisher, MeterCounters counters,
                                 ObjectMapper json, Texts texts) {
        this.metaDb = metaDb;
        this.publisher = publisher;
        this.counters = counters;
        this.json = json;
        this.texts = texts;
    }

    /** Outcome of one call; {@code replayed} when the answer comes from an earlier attempt with the same key. */
    public record Result(BatchResponse response, boolean replayed) {
    }

    /**
     * Body of the 202 answer.
     *
     * @param accepted  readings queued by this batch (stored by worker-meter shortly after)
     * @param duplicate readings already stored, or present twice in this batch: not queued again
     */
    public record BatchResponse(String idempotencyKey, int received, List<String> accepted, List<String> duplicate,
                                DataOrigin dataOrigin, Instant receivedAt) {
    }

    public Result ingest(String idempotencyKey, JsonNode body, String actor) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new InvalidBatchException(List.of(texts.get("meter.error.idempotencyKey")));
        }
        List<Parsed> readings = parse(body);
        String requestSha = requestSha256(readings);

        Optional<StoredBatch> previous = findBatch(idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().requestSha256().equals(requestSha)) {
                throw new IdempotencyConflictException(texts.get("meter.error.keyReused"));
            }
            return new Result(previous.get().response(), true);
        }

        Instant receivedAt = Instant.now();
        Set<String> alreadyStored = existing(readings.stream().map(Parsed::readingId).toList());
        Set<String> seen = new HashSet<>();
        List<String> accepted = new ArrayList<>();
        List<String> duplicate = new ArrayList<>();
        List<MeterReadingMessage> toQueue = new ArrayList<>();
        DataOrigin origin = DataOrigin.REAL;
        for (Parsed reading : readings) {
            if (reading.dataOrigin() == DataOrigin.SIMULATED) {
                origin = DataOrigin.SIMULATED;
            }
            if (alreadyStored.contains(reading.readingId()) || !seen.add(reading.readingId())) {
                duplicate.add(reading.readingId());
                continue;
            }
            accepted.add(reading.readingId());
            toQueue.add(new MeterReadingMessage(reading.readingId(), reading.meterId(), reading.periodStart(),
                    reading.periodEnd(), reading.seq(), reading.region(), reading.zone(), reading.station(),
                    reading.transformer(), reading.dataOrigin(), reading.sha256(), reading.content(),
                    idempotencyKey, actor, receivedAt));
        }

        // Throws when the broker does not confirm everything: no batch row is saved, the
        // client retries with the same key, and the readings already queued are deduplicated.
        publisher.publishAll(toQueue);

        BatchResponse response = new BatchResponse(idempotencyKey, readings.size(), accepted, duplicate, origin,
                receivedAt);
        int inserted = metaDb.update("""
                        INSERT INTO meter_batch (idempotency_key, request_sha256, reading_count, accepted_count,
                                                 duplicate_count, response, data_origin, received_at)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """, idempotencyKey, requestSha, readings.size(), accepted.size(), duplicate.size(),
                write(response), origin.name(), Timestamp.from(receivedAt));
        if (inserted == 0) {
            // The same batch was handled concurrently: answer like that request did.
            return findBatch(idempotencyKey).map(b -> new Result(b.response(), true))
                    .orElse(new Result(response, false));
        }
        if (!duplicate.isEmpty()) {
            counters.add(MeterCounters.DUPLICATES_REJECTED, duplicate.size());
        }
        log.debug("Meter batch {}: {} accepted, {} duplicate", idempotencyKey, accepted.size(), duplicate.size());
        return new Result(response, false);
    }

    private record Parsed(String readingId, String meterId, Instant periodStart, Instant periodEnd, long seq,
                          String region, String zone, String station, String transformer, DataOrigin dataOrigin,
                          byte[] content, String sha256) {
    }

    private List<Parsed> parse(JsonNode body) {
        JsonNode array = body == null ? null : body.isArray() ? body : body.get("readings");
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw new InvalidBatchException(List.of(texts.get("meter.error.empty")));
        }
        if (array.size() > MAX_BATCH) {
            throw new InvalidBatchException(List.of(texts.get("meter.error.tooMany", MAX_BATCH)));
        }
        List<String> errors = new ArrayList<>();
        List<Parsed> readings = new ArrayList<>(array.size());
        long totalBytes = 0;
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            int position = i + 1;
            if (!node.isObject()) {
                errors.add(texts.get("meter.error.notObject", position));
                continue;
            }
            String meterId = text(node, "meterId");
            Instant periodStart = instant(node, "periodStart");
            Instant periodEnd = instant(node, "periodEnd");
            JsonNode seq = node.get("seq");
            JsonNode values = node.get("values");
            DataOrigin origin = origin(node);
            if (meterId == null || meterId.length() > 64) {
                errors.add(texts.get("meter.error.meterId", position));
            }
            if (periodStart == null || periodEnd == null || !periodEnd.isAfter(periodStart)) {
                errors.add(texts.get("meter.error.period", position));
            }
            if (seq == null || !seq.canConvertToLong() || seq.asLong() < 0) {
                errors.add(texts.get("meter.error.seq", position));
            }
            if (values == null || !values.isArray() || values.isEmpty()) {
                errors.add(texts.get("meter.error.values", position));
            }
            if (origin == null) {
                errors.add(texts.get("meter.error.dataOrigin", position));
            }
            byte[] content = bytes(node);
            if (content.length > MAX_READING_BYTES) {
                errors.add(texts.get("meter.error.tooLarge", position, MAX_READING_BYTES / 1024));
            }
            totalBytes += content.length;
            if (errors.isEmpty()) {
                String readingId = MeterReadings.readingId(meterId, periodStart);
                readings.add(new Parsed(readingId, meterId, periodStart, periodEnd, seq.asLong(),
                        text(node, "region"), text(node, "zone"), text(node, "station"), text(node, "transformer"),
                        origin, content, MeterReadings.sha256(content)));
            }
        }
        if (totalBytes > MAX_BATCH_BYTES) {
            errors.add(texts.get("meter.error.batchTooLarge", MAX_BATCH_BYTES / (1024 * 1024)));
        }
        if (!errors.isEmpty()) {
            throw new InvalidBatchException(errors);
        }
        return readings;
    }

    private Set<String> existing(List<String> readingIds) {
        if (readingIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(metaDb.queryForList(
                "SELECT idempotency_key FROM file_record WHERE idempotency_key = ANY (?)", String.class,
                (Object) readingIds.toArray(String[]::new)));
    }

    private record StoredBatch(String requestSha256, BatchResponse response) {
    }

    private Optional<StoredBatch> findBatch(String key) {
        return metaDb.query("SELECT request_sha256, response::text AS response FROM meter_batch WHERE idempotency_key = ?",
                (rs, i) -> new StoredBatch(rs.getString("request_sha256"), read(rs.getString("response"))), key)
                .stream().findFirst();
    }

    /** Fingerprint of a batch: the ids and hashes of its readings, in order. */
    private static String requestSha256(List<Parsed> readings) {
        MessageDigest digest = Sha256.newDigest();
        for (Parsed reading : readings) {
            digest.update((reading.readingId() + ":" + reading.sha256() + ";").getBytes(StandardCharsets.UTF_8));
        }
        return Sha256.hex(digest);
    }

    private byte[] bytes(JsonNode node) {
        try {
            return json.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(BatchResponse response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private BatchResponse read(String text) {
        try {
            return json.readValue(text, BatchResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable stored batch answer: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A reading without dataOrigin counts as REAL; anything else must be REAL or SIMULATED. */
    private static DataOrigin origin(JsonNode node) {
        String value = text(node, "dataOrigin");
        if (value == null) {
            return DataOrigin.REAL;
        }
        try {
            return DataOrigin.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The batch is refused as a whole (400), with every problem found. */
    public static class InvalidBatchException extends RuntimeException {
        private final List<String> errors;

        public InvalidBatchException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    /** The key was already used for a different batch (422). */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }
}
