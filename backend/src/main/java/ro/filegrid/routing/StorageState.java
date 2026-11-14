package ro.filegrid.routing;

import java.time.Instant;

/**
 * What Agent 01 knows about a storage at the moment of a decision.
 *
 * @param status        UP / DOWN from the last health probe, UNKNOWN when there is no recent probe
 * @param lastCheck     when the storage was last probed, or {@code null}
 * @param latencyMs     measured latency, or {@code null} when nothing was measured
 * @param latencySource where {@code latencyMs} comes from (see {@link #SOURCE_HEALTH_PROBE})
 */
public record StorageState(Status status, Instant lastCheck, Long latencyMs, String latencySource) {

    /**
     * Latency of the periodic health probe. Until the real p95 of store
     * operations is measured (Stage 8), this is the latency used for scoring;
     * decisions record the source so the explanation stays truthful.
     */
    public static final String SOURCE_HEALTH_PROBE = "HEALTH_PROBE";

    public enum Status {
        UP,
        DOWN,
        UNKNOWN
    }

    public static StorageState unknown() {
        return new StorageState(Status.UNKNOWN, null, null, null);
    }

    public boolean isUp() {
        return status == Status.UP;
    }
}
