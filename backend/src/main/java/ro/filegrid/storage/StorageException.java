package ro.filegrid.storage;

/** A storage operation failed (network, protocol or server error). Usually retryable. */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
