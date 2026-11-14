package ro.filegrid.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Uniform access to one physical storage. Implementations stream: they never
 * hold a whole object in memory, and they compute the SHA-256 of what they
 * send while sending it.
 *
 * <p>Keys follow the layout produced by {@link StorageKeys}.
 */
public interface StorageAdapter {

    StorageType type();

    /**
     * Writes an object, replacing any existing object with the same key.
     *
     * @param key      storage key
     * @param content  content to store; read to the end, never closed by the adapter
     * @param size     expected size in bytes, or {@code -1} when unknown (not every storage accepts that)
     * @param metadata technical metadata to keep with the object where the storage supports it;
     *                 must never contain personal data
     * @throws StorageEligibilityException when this storage cannot hold the object by design
     * @throws StorageException            when the write fails
     */
    StoredObject put(String key, InputStream content, long size, Map<String, String> metadata);

    /**
     * Writes an object from a source that can be reopened. Storages that support
     * resuming continue an interrupted transfer instead of starting over.
     */
    default StoredObject put(String key, StreamSource source, long size, Map<String, String> metadata) {
        try (InputStream content = source.open()) {
            return put(key, content, size, metadata);
        } catch (IOException e) {
            throw new StorageException("Cannot read the source for " + key, e);
        }
    }

    /**
     * Opens the object for reading. The caller must close the returned stream.
     *
     * @throws StorageObjectNotFoundException when the key does not exist
     */
    InputStream get(String key);

    boolean exists(String key);

    /** Deletes the object. Deleting a missing key is not an error. */
    void delete(String key);

    /** Probes the storage. Never throws: a failure is reported as a down result. */
    StorageHealthResult health();
}
