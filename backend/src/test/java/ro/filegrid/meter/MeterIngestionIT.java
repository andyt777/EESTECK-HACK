package ro.filegrid.meter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.storage.database.StoreDbConfig;
import ro.filegrid.testsupport.TestImages;

/**
 * Stage 5: meter readings are never lost and never stored twice, whatever the
 * client retries. api and worker-meter run in the same context here, against
 * real PostgreSQL (meta-db, store-db) and RabbitMQ.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "worker"})
@Testcontainers
class MeterIngestionIT {

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(TestImages.RABBITMQ);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", META_DB::getJdbcUrl);
        registry.add("spring.datasource.username", META_DB::getUsername);
        registry.add("spring.datasource.password", META_DB::getPassword);
        registry.add("filegrid.store-db.url", STORE_DB::getJdbcUrl);
        registry.add("filegrid.store-db.username", STORE_DB::getUsername);
        registry.add("filegrid.store-db.password", STORE_DB::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("filegrid.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("filegrid.storage.s3.access-key", () -> "unused");
        registry.add("filegrid.storage.s3.secret-key", () -> "unused");
        registry.add("filegrid.storage.ftp.host", () -> "127.0.0.1");
        registry.add("filegrid.storage.ftp.port", () -> 1);
        registry.add("filegrid.storage.health-interval", () -> "1h");
        registry.add("filegrid.meter.reconciliation-interval", () -> "1h");
        registry.add("filegrid.worker.categories", () -> "METER_READING");
        registry.add("filegrid.worker.concurrency", () -> "2");
        registry.add("filegrid.worker.meter-batch-size", () -> "50");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate metaDb;

    @Autowired
    @Qualifier(StoreDbConfig.QUALIFIER)
    private JdbcTemplate storeDb;

    @Autowired
    private MeterReconciliation reconciliation;

    @Test
    @DisplayName("The same batch sent 3 times (same Idempotency-Key) -> exactly one record per readingId")
    void sameBatchThreeTimesStoresEveryReadingOnce() {
        String meter = "MTR-" + UUID.randomUUID();
        ObjectNode batch = batch(meter, 0, 120);
        String key = "batch-" + UUID.randomUUID();

        ResponseEntity<JsonNode> first = post(batch, key);
        ResponseEntity<JsonNode> second = post(batch, key);
        ResponseEntity<JsonNode> third = post(batch, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getBody().get("accepted")).hasSize(120);
        assertThat(first.getBody().get("duplicate")).isEmpty();
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        for (ResponseEntity<JsonNode> retry : List.of(second, third)) {
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(retry.getBody()).isEqualTo(first.getBody());
        }

        awaitStored(meter, 120);
        assertExactlyOncePerReading(meter, 120);
    }

    @Test
    @DisplayName("The same readings sent 3 times under different keys -> stored once, the rest reported as duplicates")
    void sameReadingsUnderNewKeysAreDeduplicated() {
        String meter = "MTR-" + UUID.randomUUID();
        ObjectNode batch = batch(meter, 0, 60);

        ResponseEntity<JsonNode> first = post(batch, "k1-" + UUID.randomUUID());
        // Sent again before and after worker-meter stored the first copy.
        ResponseEntity<JsonNode> immediate = post(batch, "k2-" + UUID.randomUUID());
        awaitStored(meter, 60);
        ResponseEntity<JsonNode> later = post(batch, "k3-" + UUID.randomUUID());

        assertThat(first.getBody().get("accepted")).hasSize(60);
        assertThat(immediate.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(later.getBody().get("accepted")).isEmpty();
        assertThat(later.getBody().get("duplicate")).hasSize(60);

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queueDepth()).isZero());
        assertExactlyOncePerReading(meter, 60);

        // getMessageCount() excludes unacknowledged messages: the worker may still be finishing the last
        // batch (and adding its rejected duplicates) after the queue looks empty, so wait for the counter.
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            MeterReconciliation.Snapshot snapshot = reconciliation.reconcileOnce();
            assertThat(snapshot.duplicatesWritten()).isZero();
            assertThat(snapshot.duplicatesRejected()).isGreaterThanOrEqualTo(120);
        });
    }

    @Test
    @DisplayName("A duplicate inside one batch is stored once and reported as duplicate")
    void duplicateInsideOneBatch() {
        String meter = "MTR-" + UUID.randomUUID();
        ObjectNode batch = batch(meter, 0, 3);
        ((ArrayNode) batch.get("readings")).add(batch.get("readings").get(0).deepCopy());

        ResponseEntity<JsonNode> response = post(batch, "dup-" + UUID.randomUUID());

        assertThat(response.getBody().get("received").asInt()).isEqualTo(4);
        assertThat(response.getBody().get("accepted")).hasSize(3);
        assertThat(response.getBody().get("duplicate")).hasSize(1);
        awaitStored(meter, 3);
        assertExactlyOncePerReading(meter, 3);
    }

    @Test
    @DisplayName("Missing seq numbers are counted as gaps by the reconciliation")
    void gapsAreCounted() {
        long before = reconciliation.reconcileOnce().gaps();
        String meter = "MTR-" + UUID.randomUUID();
        ObjectNode batch = json.createObjectNode();
        ArrayNode readings = batch.putArray("readings");
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        for (long seq : new long[] {1, 2, 4, 7}) {
            readings.add(reading(meter, start.plus(seq * 15, ChronoUnit.MINUTES), seq));
        }
        post(batch, "gaps-" + UUID.randomUUID());
        awaitStored(meter, 4);

        assertThat(reconciliation.reconcileOnce().gaps() - before).isEqualTo(3);
    }

    @Test
    @DisplayName("Invalid batches are refused with every problem listed; a reused key with other content is 422")
    void invalidBatches() {
        ObjectNode valid = batch("MTR-" + UUID.randomUUID(), 0, 2);
        assertThat(post(valid, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ObjectNode broken = json.createObjectNode();
        broken.putArray("readings").addObject().put("meterId", "M1").put("seq", -1);
        ResponseEntity<JsonNode> refused = post(broken, "bad-" + UUID.randomUUID());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().get("errors").size()).isGreaterThanOrEqualTo(3);

        String key = "reuse-" + UUID.randomUUID();
        assertThat(post(valid, key).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> reused = post(batch("MTR-" + UUID.randomUUID(), 0, 2), key);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("GET /api/meters/stats reports the readings of the last minute and their origin")
    void statsReportsRecentReadings() {
        String meter = "MTR-" + UUID.randomUUID();
        post(batch(meter, 0, 10), "stats-" + UUID.randomUUID());
        awaitStored(meter, 10);
        reconciliation.reconcile();

        JsonNode stats = rest.getForObject("/meters/stats", JsonNode.class);
        assertThat(stats.get("readingsLastMinute").asLong()).isGreaterThanOrEqualTo(10);
        assertThat(stats.get("duplicatesWritten").asLong()).isZero();
        assertThat(stats.get("dataOrigin").asText()).isEqualTo("SIMULATED");
        assertThat(stats.get("activeMeters").asLong()).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<JsonNode> post(ObjectNode body, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.postForEntity("/meters/readings", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ObjectNode batch(String meterId, long firstSeq, int count) {
        ObjectNode batch = json.createObjectNode();
        ArrayNode readings = batch.putArray("readings");
        Instant start = Instant.parse("2026-11-10T00:00:00Z");
        for (int i = 0; i < count; i++) {
            long seq = firstSeq + i;
            readings.add(reading(meterId, start.plus(seq * 15, ChronoUnit.MINUTES), seq));
        }
        return batch;
    }

    private ObjectNode reading(String meterId, Instant periodStart, long seq) {
        ObjectNode reading = json.createObjectNode()
                .put("meterId", meterId)
                .put("region", "Banat")
                .put("zone", "Timiș")
                .put("station", "ST-TM-01")
                .put("transformer", "PT-TM-01-03")
                .put("periodStart", periodStart.toString())
                .put("periodEnd", periodStart.plus(15, ChronoUnit.MINUTES).toString())
                .put("intervalMinutes", 15)
                .put("seq", seq)
                .put("dataOrigin", "SIMULATED");
        ArrayNode values = reading.putArray("values");
        for (int minute = 0; minute < 15; minute++) {
            values.addObject().put("minute", minute).put("kwhImport", 0.01 * minute).put("kwhExport", 0.0)
                    .put("voltage", 230.1).put("current", 1.2);
        }
        return reading;
    }

    private void awaitStored(String meterId, int expected) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(storeDb.queryForObject("SELECT count(*) FROM meter_reading_blob WHERE meter_id = ?",
                        Integer.class, meterId)).isEqualTo(expected));
    }

    private void assertExactlyOncePerReading(String meterId, int expected) {
        assertThat(storeDb.queryForObject("""
                SELECT count(DISTINCT reading_id) FROM meter_reading_blob WHERE meter_id = ?
                """, Integer.class, meterId)).isEqualTo(expected);
        assertThat(storeDb.queryForObject("SELECT count(*) FROM meter_reading_blob WHERE meter_id = ?",
                Integer.class, meterId)).isEqualTo(expected);
        List<Integer> perReading = metaDb.queryForList("""
                SELECT count(*) FROM file_record r JOIN file_context c ON c.file_id = r.id
                 WHERE c.meter_id = ? GROUP BY r.idempotency_key
                """, Integer.class, meterId);
        assertThat(perReading).hasSize(expected).allMatch(n -> n == 1);
        assertThat(metaDb.queryForObject("""
                SELECT count(*) FROM file_record r JOIN file_context c ON c.file_id = r.id
                 WHERE c.meter_id = ? AND r.status = 'STORED_VERIFIED' AND r.integrity_status = 'VERIFIED_INTACT'
                   AND r.storage_type = 'DATABASE' AND r.data_origin = 'SIMULATED'
                """, Integer.class, meterId)).isEqualTo(expected);
        // Read-back: PostgreSQL's hash of the stored bytes equals the hash computed at the api.
        assertThat(storeDb.queryForObject("""
                SELECT count(*) FROM meter_reading_blob
                 WHERE meter_id = ? AND encode(sha256(content), 'hex') = sha256
                """, Integer.class, meterId)).isEqualTo(expected);
    }

    private int queueDepth() {
        return new org.springframework.amqp.rabbit.core.RabbitAdmin(connectionFactory)
                .getQueueInfo("filegrid.jobs.meter").getMessageCount();
    }

    @Autowired
    private org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory;
}
