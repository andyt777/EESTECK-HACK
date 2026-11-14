package ro.filegrid.storage;

/** The requested key does not exist in the storage. */
public class StorageObjectNotFoundException extends StorageException {

    public StorageObjectNotFoundException(StorageType storage, String key) {
        super("Object not found in " + storage + ": " + key);
    }
}
