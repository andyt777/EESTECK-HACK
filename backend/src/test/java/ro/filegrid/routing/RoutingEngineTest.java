package ro.filegrid.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.filegrid.file.FileCategory;
import ro.filegrid.policy.Policy;
import ro.filegrid.policy.PolicyParser;
import ro.filegrid.routing.RoutingOutcome.Candidate;
import ro.filegrid.routing.RoutingOutcome.Role;
import ro.filegrid.routing.StorageState.Status;
import ro.filegrid.storage.StorageType;
import ro.filegrid.text.Texts;

class RoutingEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final Instant CHECKED_AT = NOW.minusSeconds(5);
    private static final long MB = 1024L * 1024L;
    private static final Map<StorageType, Long> ADAPTER_LIMITS = Map.of(StorageType.DATABASE, 64 * MB);

    private static Policy policy;
    private static RoutingEngine engine;

    @BeforeAll
    static void setUp() throws IOException {
        policy = PolicyParser.parse(Files.readString(Path.of("../config/policies.yaml")));
        engine = new RoutingEngine(new Texts());
    }

    private static Map<StorageType, StorageState> allUp() {
        Map<StorageType, StorageState> states = new EnumMap<>(StorageType.class);
        states.put(StorageType.DATABASE, up(4));
        states.put(StorageType.FTP, up(12));
        states.put(StorageType.S3, up(18));
        return states;
    }

    private static StorageState up(long latencyMs) {
        return new StorageState(Status.UP, CHECKED_AT, latencyMs, StorageState.SOURCE_HEALTH_PROBE);
    }

    private static StorageState down() {
        return new StorageState(Status.DOWN, CHECKED_AT, null, null);
    }

    @Test
    void selectsPrimaryForEveryCategoryWhenAllStoragesAreUp() {
        Map<StorageType, StorageState> states = allUp();

        RoutingOutcome meter = engine.evaluate(policy, FileCategory.METER_READING, 4096, states, ADAPTER_LIMITS, NOW);
        assertThat(meter.selected()).isEqualTo(StorageType.DATABASE);
        assertThat(meter.fallbackUsed()).isFalse();

        RoutingOutcome fault = engine.evaluate(policy, FileCategory.FAULT_RECORD, 5 * MB, states, ADAPTER_LIMITS, NOW);
        assertThat(fault.selected()).isEqualTo(StorageType.FTP);
        assertThat(fault.fallbackUsed()).isFalse();

        RoutingOutcome doc = engine.evaluate(policy, FileCategory.CONNECTION_DOCUMENT, 2 * MB, states, ADAPTER_LIMITS, NOW);
        assertThat(doc.selected()).isEqualTo(StorageType.S3);
        assertThat(doc.fallbackUsed()).isFalse();
        assertThat(doc.explanationRo()).contains("criptat");

        RoutingOutcome video = engine.evaluate(policy, FileCategory.DRONE_VIDEO, 500 * MB, states, ADAPTER_LIMITS, NOW);
        assertThat(video.selected()).isEqualTo(StorageType.S3);
        assertThat(video.fallbackUsed()).isFalse();
    }

    @Test
    void fallsBackWhenPrimaryIsDownForEveryCategory() {
        Map<StorageType, StorageState> dbDown = allUp();
        dbDown.put(StorageType.DATABASE, down());
        RoutingOutcome meter = engine.evaluate(policy, FileCategory.METER_READING, 4096, dbDown, ADAPTER_LIMITS, NOW);
        assertThat(meter.selected()).isEqualTo(StorageType.S3);
        assertThat(meter.fallbackUsed()).isTrue();
        assertThat(meter.fallbackReason()).contains("Database");

        Map<StorageType, StorageState> ftpDown = allUp();
        ftpDown.put(StorageType.FTP, down());
        RoutingOutcome fault = engine.evaluate(policy, FileCategory.FAULT_RECORD, 5 * MB, ftpDown, ADAPTER_LIMITS, NOW);
        assertThat(fault.selected()).isEqualTo(StorageType.S3);
        assertThat(fault.fallbackUsed()).isTrue();
        assertThat(fault.fallbackReason()).contains("FTP");

        Map<StorageType, StorageState> s3Down = allUp();
        s3Down.put(StorageType.S3, down());
        RoutingOutcome doc = engine.evaluate(policy, FileCategory.CONNECTION_DOCUMENT, 2 * MB, s3Down, ADAPTER_LIMITS, NOW);
        assertThat(doc.selected()).isEqualTo(StorageType.DATABASE);
        assertThat(doc.fallbackUsed()).isTrue();
        assertThat(doc.fallbackReason()).contains("S3");

        RoutingOutcome video = engine.evaluate(policy, FileCategory.DRONE_VIDEO, 500 * MB, s3Down, ADAPTER_LIMITS, NOW);
        assertThat(video.selected()).isEqualTo(StorageType.FTP);
        assertThat(video.fallbackUsed()).isTrue();
        assertThat(video.fallbackReason()).contains("S3");
    }

    @Test
    void storageInMaintenanceIsSkippedWithTheAdministratorsReason() {
        Map<StorageType, StorageState> s3Maintenance = allUp();
        s3Maintenance.put(StorageType.S3,
                new StorageState(Status.MAINTENANCE, CHECKED_AT, null, null, "upgrade firmware"));

        RoutingOutcome doc = engine.evaluate(policy, FileCategory.CONNECTION_DOCUMENT, 2 * MB, s3Maintenance,
                ADAPTER_LIMITS, NOW);

        assertThat(doc.selected()).isEqualTo(StorageType.DATABASE);
        assertThat(doc.fallbackUsed()).isTrue();
        assertThat(doc.candidates()).filteredOn(c -> c.storage() == StorageType.S3).singleElement()
                .satisfies(s3 -> {
                    assertThat(s3.available()).isFalse();
                    assertThat(s3.reasons()).anySatisfy(r -> {
                        assertThat(r.code()).isEqualTo(RoutingEngine.MAINTENANCE);
                        assertThat(r.text()).contains("mentenanță planificată").contains("upgrade firmware");
                    });
                });
    }

    @Test
    void excludesStoragesWhenFileExceedsSizeLimit() {
        Map<StorageType, StorageState> states = allUp();
        RoutingOutcome oversizedMeter = engine.evaluate(
                policy, FileCategory.METER_READING, 2 * MB, states, ADAPTER_LIMITS, NOW);

        Candidate dbForMeter = oversizedMeter.candidate(StorageType.DATABASE);
        assertThat(dbForMeter.eligible()).isFalse();
        assertThat(dbForMeter.hasReason(RoutingEngine.SIZE_EXCEEDED)).isTrue();
        assertThat(oversizedMeter.selected()).isEqualTo(StorageType.S3);
        assertThat(oversizedMeter.fallbackUsed()).isTrue();

        Map<StorageType, StorageState> s3Down = allUp();
        s3Down.put(StorageType.S3, down());
        RoutingOutcome oversizedDoc = engine.evaluate(
                policy, FileCategory.CONNECTION_DOCUMENT, 70 * MB, s3Down, ADAPTER_LIMITS, NOW);

        Candidate dbForDoc = oversizedDoc.candidate(StorageType.DATABASE);
        assertThat(dbForDoc.eligible()).isFalse();
        assertThat(dbForDoc.reasons())
                .anySatisfy(r -> assertThat(r.text())
                        .isEqualTo("fișierul de 70 MB depășește limita de 64 MB pentru Database"));
        assertThat(oversizedDoc.selected()).isNull();
        assertThat(oversizedDoc.pendingReason()).isNotBlank();
        assertThat(oversizedDoc.explanationRo()).contains("așteptare");
    }

    @Test
    void enforcesCapabilityConstraintsAndNeverSelectsNotInPolicy() {
        Map<StorageType, StorageState> onlyDbUp = Map.of(
                StorageType.DATABASE, up(2),
                StorageType.S3, down(),
                StorageType.FTP, down());

        RoutingOutcome video = engine.evaluate(policy, FileCategory.DRONE_VIDEO, 1 * MB, onlyDbUp, ADAPTER_LIMITS, NOW);
        Candidate dbCandidate = video.candidate(StorageType.DATABASE);
        assertThat(dbCandidate.role()).isEqualTo(Role.NOT_IN_POLICY);
        assertThat(dbCandidate.eligible()).isFalse();
        assertThat(dbCandidate.hasReason(RoutingEngine.RESUMABLE_MISSING)).isTrue();
        assertThat(video.selected()).isNull();

        RoutingOutcome meter = engine.evaluate(policy, FileCategory.METER_READING, 4096, allUp(), ADAPTER_LIMITS, NOW);
        Candidate ftpCandidate = meter.candidate(StorageType.FTP);
        assertThat(ftpCandidate.role()).isEqualTo(Role.NOT_IN_POLICY);
        assertThat(ftpCandidate.eligible()).isFalse();
        assertThat(ftpCandidate.hasReason(RoutingEngine.IDEMPOTENT_MISSING)).isTrue();
    }

    @Test
    void returnsPendingWhenAllEligibleStoragesAreDownOrUnknown() {
        Map<StorageType, StorageState> allDown = Map.of(
                StorageType.FTP, down(),
                StorageType.S3, down(),
                StorageType.DATABASE, up(3));

        RoutingOutcome fault = engine.evaluate(policy, FileCategory.FAULT_RECORD, 5 * MB, allDown, ADAPTER_LIMITS, NOW);
        assertThat(fault.selected()).isNull();
        assertThat(fault.pendingReason()).contains("FTP").contains("S3");
        assertThat(fault.explanationRo()).contains("rămas în așteptare");

        Map<StorageType, StorageState> allUnknown = Map.of(
                StorageType.FTP, StorageState.unknown(),
                StorageType.S3, StorageState.unknown(),
                StorageType.DATABASE, StorageState.unknown());
        RoutingOutcome unknownFault = engine.evaluate(
                policy, FileCategory.FAULT_RECORD, 5 * MB, allUnknown, ADAPTER_LIMITS, NOW);
        assertThat(unknownFault.selected()).isNull();
        assertThat(unknownFault.candidate(StorageType.FTP).hasReason(RoutingEngine.STATE_UNKNOWN)).isTrue();
    }

    @Test
    void evaluationIsDeterministic() {
        Map<StorageType, StorageState> states = allUp();

        RoutingOutcome first = engine.evaluate(policy, FileCategory.DRONE_VIDEO, 250 * MB, states, ADAPTER_LIMITS, NOW);
        RoutingOutcome second = engine.evaluate(policy, FileCategory.DRONE_VIDEO, 250 * MB, states, ADAPTER_LIMITS, NOW);

        assertThat(first).isEqualTo(second);
    }
}
