package ro.filegrid.file;

/** Result of comparing the stored content with the original SHA-256. */
public enum IntegrityStatus {
    UNVERIFIED,
    VERIFIED_INTACT,
    MISMATCH
}
