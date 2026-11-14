package ro.filegrid.routing;

import java.util.Map;
import ro.filegrid.storage.StorageType;

/**
 * Source of the live storage facts used for routing (availability, measured
 * latency). The default implementation reads {@code storage_health}; Stage 8
 * can supply the measured p95 of real store operations through the same interface.
 */
public interface StorageStateProvider {

    /** One entry per storage; a storage without recent data is reported as UNKNOWN. */
    Map<StorageType, StorageState> currentStates();
}
