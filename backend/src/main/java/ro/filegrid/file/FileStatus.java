package ro.filegrid.file;

/** Lifecycle of a file. Every state shown in the UI is one of these real states. */
public enum FileStatus {
    RECEIVED,
    ANALYZING,
    CATEGORIZED,
    DESTINATION_SELECTED,
    TRANSFERRING,
    TRANSFER_RESUMED,
    STORED_VERIFIED,
    PENDING,
    TRANSFER_FAILED
}
