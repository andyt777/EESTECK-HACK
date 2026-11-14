package ro.filegrid.file;

/** Kinds of entries in the audit trail of a file. */
public enum FileEventType {
    RECEIVED,
    CLASSIFIED,
    ROUTED,
    TRANSFER_STARTED,
    TRANSFER_RESUMED,
    STORED,
    VERIFIED,
    ACCESSED,
    DOWNLOADED,
    STATUS_CHANGED,
    FALLBACK,
    INTEGRITY_INCIDENT
}
