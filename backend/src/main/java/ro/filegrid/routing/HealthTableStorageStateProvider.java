package ro.filegrid.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ro.filegrid.storage.StorageType;

/**
 * Reads the result of the periodic health probe from {@code storage_health}
 * (meta-db), so it works in any role. A probe older than
 * {@code filegrid.routing.health-max-age} is not trusted: the storage is then
 * UNKNOWN, which Agent 01 treats as not available.
 */
@Component
public class HealthTableStorageStateProvider implements StorageStateProvider {

    private final JdbcTemplate metaDb;
    private final Duration maxAge;

    public HealthTableStorageStateProvider(JdbcTemplate metaDb,
                                           @Value("${filegrid.routing.health-max-age}") Duration maxAge) {
        this.metaDb = metaDb;
        this.maxAge = maxAge;
    }

    @Override
    public Map<StorageType, StorageState> currentStates() {
        Map<StorageType, StorageState> states = new EnumMap<>(StorageType.class);
        for (StorageType storage : StorageType.values()) {
            states.put(storage, StorageState.unknown());
        }
        Instant oldestTrusted = Instant.now().minus(maxAge);
        metaDb.query("SELECT storage, status, last_check, latency_ms FROM storage_health", rs -> {
            StorageType storage = StorageType.valueOf(rs.getString("storage"));
            Instant lastCheck = rs.getTimestamp("last_check").toInstant();
            if (lastCheck.isBefore(oldestTrusted)) {
                states.put(storage, new StorageState(StorageState.Status.UNKNOWN, lastCheck, null, null));
                return;
            }
            boolean up = "UP".equals(rs.getString("status"));
            states.put(storage, new StorageState(up ? StorageState.Status.UP : StorageState.Status.DOWN,
                    lastCheck, up ? rs.getLong("latency_ms") : null,
                    up ? StorageState.SOURCE_HEALTH_PROBE : null));
        });
        // Planned maintenance wins over the probe: the storage may answer, but it is out of routing.
        metaDb.query("SELECT storage, reason FROM storage_maintenance", rs -> {
            StorageType storage = StorageType.valueOf(rs.getString("storage"));
            StorageState probed = states.get(storage);
            states.put(storage, new StorageState(StorageState.Status.MAINTENANCE, probed.lastCheck(), null, null,
                    rs.getString("reason")));
        });
        return states;
    }
}
