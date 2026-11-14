package ro.filegrid.policy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import ro.filegrid.file.FileCategory;
import ro.filegrid.storage.StorageType;

/**
 * The routing policy of Agent 01, as written in {@code config/policies.yaml}.
 *
 * <p>Fields are boxed on purpose: a value missing from the YAML stays
 * {@code null} and is reported by {@link PolicyValidator} instead of silently
 * becoming {@code false} or {@code 0}. A policy handed out by
 * {@link PolicyService} has passed validation, so none of its fields is null
 * (except {@link StorageCapabilities#maxSizeBytes()}, where null means "no limit").
 */
public record Policy(Integer schemaVersion,
                     Map<StorageType, StorageCapabilities> storages,
                     Map<FileCategory, CategoryPolicy> categories) {

    public CategoryPolicy category(FileCategory category) {
        return categories.get(category);
    }

    /**
     * What a storage can do.
     *
     * @param maxSizeBytes largest object accepted, or {@code null} for no limit
     */
    public record StorageCapabilities(Long maxSizeBytes, Boolean resumable, Boolean immediateRead,
                                      Boolean idempotentWrites) {
    }

    public record CategoryPolicy(Detection detection, StorageType primary, List<StorageType> fallbacks,
                                 Constraints constraints, Weights weights, Retry retry, Sla sla) {
    }

    /**
     * How a file of this category is recognised.
     *
     * @param extensions     lower-case, without the dot
     * @param mimeTypes      exact types or {@code type/*}
     * @param requiredFields context fields that must be filled in (zone, station, meterId, ...)
     */
    public record Detection(List<String> extensions, List<String> mimeTypes, List<String> requiredFields) {
    }

    /**
     * What the category needs from a storage.
     *
     * @param maxSizeBytes optional per-storage size limits, stricter than the storage's own
     */
    public record Constraints(Boolean needsResumable, Boolean needsEncryption, Boolean needsImmediateRead,
                              Boolean idempotent, Map<StorageType, Long> maxSizeBytes) {
    }

    public record Weights(Double sizeFit, Double resumable, Double availability, Double measuredLatencyP95,
                          Double policyPreference) {

        public double total() {
            return sizeFit + resumable + availability + measuredLatencyP95 + policyPreference;
        }
    }

    public record Retry(Integer maxAttempts, Duration initialBackoff, Duration maxBackoff) {
    }

    public record Sla(Long maxPendingSeconds, Long maxStoreP95Seconds) {
    }
}
