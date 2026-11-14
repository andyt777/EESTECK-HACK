package ro.filegrid.storage;

/**
 * The storage cannot accept this object by design (for example it exceeds the
 * size limit of the Database storage). Retrying does not help: another storage
 * must be chosen.
 */
public class StorageEligibilityException extends StorageException {

    public StorageEligibilityException(String message) {
        super(message);
    }
}
