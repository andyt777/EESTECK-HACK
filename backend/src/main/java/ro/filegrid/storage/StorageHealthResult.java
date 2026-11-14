package ro.filegrid.storage;

/**
 * Outcome of one health probe.
 *
 * @param up        whether the storage answered correctly
 * @param latencyMs how long the probe took
 * @param detail    short error description when down, otherwise {@code null}
 */
public record StorageHealthResult(boolean up, long latencyMs, String detail) {

    public static StorageHealthResult up(long latencyMs) {
        return new StorageHealthResult(true, latencyMs, null);
    }

    public static StorageHealthResult down(long latencyMs, String detail) {
        return new StorageHealthResult(false, latencyMs, detail);
    }
}
