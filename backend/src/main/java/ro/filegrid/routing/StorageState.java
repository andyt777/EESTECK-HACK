package ro.filegrid.routing;

import java.time.Instant;

/**
 * What Agent 01 knows about a storage at the moment of a decision.
 *
 * @param status        UP / DOWN from the last health probe, UNKNOWN when there is no recent probe
 * @param lastCheck     when the storage was last probed, or {@code null}
 * @param latencyMs     measured latency, or {@code null} when nothing was measured
 * @param latencySource where {@code latencyMs} comes from (see {@link #SOURCE_HEALTH_PROBE})
 * @param note          for MAINTENANCE: the reason given by the administrator, otherwise {@code null}
 */
public record StorageState(Status status, Instant lastCheck, Long latencyMs, String latencySource, String note) {

    public StorageState(Status status, Instant lastCheck, Long latencyMs, String latencySource) {
        this(status, lastCheck, latencyMs, latencySource, null);
    }

    /**
     * Latency of the periodic health probe. Until the real p95 of store
     * operations is measured (Stage 8), this is the latency used for scoring;
     * decisions record the source so the explanation stays truthful.
     */
    public static final String SOURCE_HEALTH_PROBE = "HEALTH_PROBE";

    public enum Status {
        UP,
        DOWN,
        UNKNOWN,
        /** Answers its probe, but an administrator took it out of routing (planned maintenance). */
        MAINTENANCE
    }

    public static StorageState unknown() {
        return new StorageState(Status.UNKNOWN, null, null, null);
    }

    public boolean isUp() {
        return status == Status.UP;
    }
}
