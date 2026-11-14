package ro.filegrid.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ro.filegrid.storage.StorageType;

/** One file known to FileGrid: identity, state, where it is stored and its integrity. */
@Entity
@Table(name = "file_record")
public class FileRecord {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FileCategory category;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** SHA-256 of the original content, as received. */
    @Column(name = "sha256_plain", length = 64)
    private String sha256Plain;

    /** SHA-256 of what was written to the storage (differs from plain when encrypted). */
    @Column(name = "sha256_stored", length = 64)
    private String sha256Stored;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FileStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "integrity_status", nullable = false, length = 32)
    private IntegrityStatus integrityStatus = IntegrityStatus.UNVERIFIED;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_type", length = 16)
    private StorageType storageType;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(nullable = false)
    private boolean encrypted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Sensitivity sensitivity = Sensitivity.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_origin", nullable = false, length = 16)
    private DataOrigin dataOrigin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FileSource source;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "stored_at")
    private Instant storedAt;

    @Column(name = "idempotency_key", length = 128, unique = true)
    private String idempotencyKey;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    protected FileRecord() {
        // for JPA
    }

    /** A file that has just been received (status {@link FileStatus#RECEIVED}). */
    public FileRecord(UUID id, FileCategory category, String originalName, long sizeBytes,
                      DataOrigin dataOrigin, FileSource source, String uploadedBy, Instant receivedAt) {
        this.id = id;
        this.category = category;
        this.originalName = originalName;
        this.sizeBytes = sizeBytes;
        this.dataOrigin = dataOrigin;
        this.source = source;
        this.uploadedBy = uploadedBy;
        this.receivedAt = receivedAt;
        this.status = FileStatus.RECEIVED;
    }

    public UUID getId() {
        return id;
    }

    public FileCategory getCategory() {
        return category;
    }

    public void setCategory(FileCategory category) {
        this.category = category;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256Plain() {
        return sha256Plain;
    }

    public void setSha256Plain(String sha256Plain) {
        this.sha256Plain = sha256Plain;
    }

    public String getSha256Stored() {
        return sha256Stored;
    }

    public void setSha256Stored(String sha256Stored) {
        this.sha256Stored = sha256Stored;
    }

    public FileStatus getStatus() {
        return status;
    }

    public void setStatus(FileStatus status) {
        this.status = status;
    }

    public IntegrityStatus getIntegrityStatus() {
        return integrityStatus;
    }

    public void setIntegrityStatus(IntegrityStatus integrityStatus) {
        this.integrityStatus = integrityStatus;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }

    public StorageType getStorageType() {
        return storageType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    /** Records where the file is (or will be) stored. */
    public void setStorageLocation(StorageType storageType, String storageKey) {
        this.storageType = storageType;
        this.storageKey = storageKey;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }

    public Sensitivity getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(Sensitivity sensitivity) {
        this.sensitivity = sensitivity;
    }

    public DataOrigin getDataOrigin() {
        return dataOrigin;
    }

    public FileSource getSource() {
        return source;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getStoredAt() {
        return storedAt;
    }

    public void setStoredAt(Instant storedAt) {
        this.storedAt = storedAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
