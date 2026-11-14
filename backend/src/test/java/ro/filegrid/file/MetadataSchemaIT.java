package ro.filegrid.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.storage.StorageHealthResult;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.health.StorageHealthMonitor;
import ro.filegrid.testsupport.TestImages;

/**
 * Starts the whole application (api role) against two real PostgreSQL
 * instances. Hibernate runs with {@code ddl-auto=validate}, so the context
 * only starts if the Flyway migrations and the entities agree.
 * S3 and FTP are deliberately unreachable here.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("api")
@Testcontainers
class MetadataSchemaIT {

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", META_DB::getJdbcUrl);
        registry.add("spring.datasource.username", META_DB::getUsername);
        registry.add("spring.datasource.password", META_DB::getPassword);
        registry.add("filegrid.store-db.url", STORE_DB::getJdbcUrl);
        registry.add("filegrid.store-db.username", STORE_DB::getUsername);
        registry.add("filegrid.store-db.password", STORE_DB::getPassword);
        // Nothing listens on port 1: both storages must be reported DOWN, not break the app.
        registry.add("filegrid.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("filegrid.storage.s3.access-key", () -> "unused");
        registry.add("filegrid.storage.s3.secret-key", () -> "unused");
        registry.add("filegrid.storage.ftp.host", () -> "127.0.0.1");
        registry.add("filegrid.storage.ftp.port", () -> 1);
        registry.add("filegrid.storage.ftp.connect-timeout", () -> "2s");
        registry.add("filegrid.storage.health-interval", () -> "1h");
    }

    @Autowired
    FileRecordRepository files;
    @Autowired
    FileContextRepository contexts;
    @Autowired
    RoutingDecisionRepository decisions;
    @Autowired
    FileEventRepository events;
    @Autowired
    JdbcTemplate metaDb;
    @Autowired
    StorageHealthMonitor healthMonitor;
    @Autowired
    CurrentActorProvider actor;
    @Autowired
    ObjectMapper json;

    @Test
    void savesAndReadsTheWholeFileModel() {
        UUID fileId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-14T10:15:30Z");

        FileRecord file = new FileRecord(fileId, FileCategory.FAULT_RECORD, "avarie-statia-x.pdf", 2_500_000,
                DataOrigin.SIMULATED, FileSource.UI, actor.currentActor(), now);
        file.setContentType("application/pdf");
        file.setSha256Plain("a".repeat(64));
        file.setStatus(FileStatus.DESTINATION_SELECTED);
        file.setStorageLocation(StorageType.FTP, "faults/2026/11/14/" + fileId);
        files.saveAndFlush(file);

        FileContext context = new FileContext(fileId);
        context.setZone("Banat");
        context.setStation("Statia X");
        contexts.saveAndFlush(context);

        ObjectNode candidates = json.createObjectNode();
        candidates.putArray("list").addObject().put("storage", "FTP").put("eligible", true).put("score", 0.92);
        RoutingDecision decision = new RoutingDecision(UUID.randomUUID(), fileId, "yaml-1",
                json.createObjectNode().put("declared", "FAULT_RECORD"),
                json.createObjectNode().put("sizeBytes", 2_500_000),
                candidates, StorageType.FTP, now);
        decision.setExplanationRo("Fișierul a fost direcționat către FTP.");
        decisions.saveAndFlush(decision);

        events.saveAndFlush(new FileEvent(fileId, FileEventType.RECEIVED, actor.currentActor(),
                json.valueToTree(Map.of("sizeBytes", 2_500_000)), "trace-1", now));

        FileRecord loaded = files.findById(fileId).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(FileStatus.DESTINATION_SELECTED);
        assertThat(loaded.getIntegrityStatus()).isEqualTo(IntegrityStatus.UNVERIFIED);
        assertThat(loaded.getSensitivity()).isEqualTo(Sensitivity.NONE);
        assertThat(loaded.getDataOrigin()).isEqualTo(DataOrigin.SIMULATED);
        assertThat(loaded.getUploadedBy()).isEqualTo("demo-user");
        assertThat(loaded.getReceivedAt()).isEqualTo(now);
        assertThat(contexts.findById(fileId).orElseThrow().getStation()).isEqualTo("Statia X");

        RoutingDecision loadedDecision = decisions.findFirstByFileIdOrderByDecidedAtDesc(fileId).orElseThrow();
        assertThat(loadedDecision.getCandidates().get("list").get(0).get("score").asDouble()).isEqualTo(0.92);
        assertThat(loadedDecision.getExplanationRo()).contains("FTP");

        assertThat(events.findByFileIdOrderByAtAsc(fileId))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getType()).isEqualTo(FileEventType.RECEIVED);
                    assertThat(event.getDetails().get("sizeBytes").asLong()).isEqualTo(2_500_000);
                });
    }

    @Test
    void idempotencyKeyIsUnique() {
        files.saveAndFlush(newFile("reading-1"));

        assertThatThrownBy(() -> files.saveAndFlush(newFile("reading-1")))
                .isInstanceOf(DataAccessException.class);
        assertThat(files.findByIdempotencyKey("reading-1")).isPresent();
    }

    @Test
    void theDatabaseRejectsInvalidStates() {
        UUID id = files.saveAndFlush(newFile(null)).getId();

        assertThatThrownBy(() -> metaDb.update("UPDATE file_record SET status = 'DONE' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theAuditTrailIsAppendOnly() {
        UUID fileId = files.saveAndFlush(newFile(null)).getId();
        events.saveAndFlush(new FileEvent(fileId, FileEventType.DOWNLOADED, "demo-user",
                json.createObjectNode(), null, Instant.now()));

        assertThatThrownBy(() -> metaDb.update("UPDATE file_event SET actor = 'someone-else' WHERE file_id = ?", fileId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void healthCheckRecordsEveryStorageAndSurvivesUnreachableOnes() {
        healthMonitor.checkAll();

        Map<StorageType, StorageHealthResult> results = healthMonitor.lastResults();
        assertThat(results).containsOnlyKeys(StorageType.values());
        assertThat(results.get(StorageType.DATABASE).up()).isTrue();
        assertThat(results.get(StorageType.S3).up()).isFalse();
        assertThat(results.get(StorageType.FTP).up()).isFalse();

        assertThat(metaDb.queryForObject(
                "SELECT status FROM storage_health WHERE storage = 'DATABASE'", String.class)).isEqualTo("UP");
        assertThat(metaDb.queryForObject(
                "SELECT status FROM storage_health WHERE storage = 'S3'", String.class)).isEqualTo("DOWN");
        assertThat(metaDb.queryForObject(
                "SELECT count(*) FROM storage_health", Integer.class)).isEqualTo(3);
    }

    @Test
    void storeDbHasItsOwnSchema() {
        // stored_blob lives in store-db only; meta-db must not contain it.
        assertThat(metaDb.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'stored_blob'", Integer.class))
                .isZero();
        assertThat(metaDb.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'file_record'", Integer.class))
                .isEqualTo(1);
    }

    private FileRecord newFile(String idempotencyKey) {
        FileRecord file = new FileRecord(UUID.randomUUID(), FileCategory.METER_READING, "reading.json", 1_000,
                DataOrigin.SIMULATED, FileSource.SIMULATOR, "simulator", Instant.now());
        file.setIdempotencyKey(idempotencyKey);
        return file;
    }
}
