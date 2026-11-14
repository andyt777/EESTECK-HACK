package ro.filegrid.storage;

/**
 * Result of a successful write.
 *
 * @param key         storage key of the object
 * @param sizeWritten number of bytes sent to the storage
 * @param sha256      lowercase hex SHA-256 of the bytes sent, computed while streaming
 */
public record StoredObject(String key, long sizeWritten, String sha256) {
}
