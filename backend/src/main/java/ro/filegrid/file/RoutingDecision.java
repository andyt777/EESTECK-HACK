package ro.filegrid.file;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ro.filegrid.storage.StorageType;

/**
 * A routing decision of Agent 01, saved before the transfer starts. It keeps
 * the inputs and every evaluated candidate, so the explanation shown later is
 * the real one.
 */
@Entity
@Table(name = "routing_decision")
public class RoutingDecision {

    @Id
    private UUID id;

    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(name = "policy_version", nullable = false, length = 64)
    private String policyVersion;

    /** How the category was detected (declared, MIME, extension, warnings). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode detection;

    /** File characteristics that were used (size, storage states, measured latency). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode features;

    /** One entry per storage: eligible, score, reasons. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode candidates;

    @Enumerated(EnumType.STRING)
    @Column(name = "selected_storage", length = 16)
    private StorageType selectedStorage;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "fallback_reason", columnDefinition = "text")
    private String fallbackReason;

    @Column(name = "explanation_ro", columnDefinition = "text")
    private String explanationRo;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected RoutingDecision() {
        // for JPA
    }

    public RoutingDecision(UUID id, UUID fileId, String policyVersion, JsonNode detection, JsonNode features,
                           JsonNode candidates, StorageType selectedStorage, Instant decidedAt) {
        this.id = id;
        this.fileId = fileId;
        this.policyVersion = policyVersion;
        this.detection = detection;
        this.features = features;
        this.candidates = candidates;
        this.selectedStorage = selectedStorage;
        this.decidedAt = decidedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public JsonNode getDetection() {
        return detection;
    }

    public JsonNode getFeatures() {
        return features;
    }

    public JsonNode getCandidates() {
        return candidates;
    }

    public StorageType getSelectedStorage() {
        return selectedStorage;
    }

    public void setSelectedStorage(StorageType selectedStorage) {
        this.selectedStorage = selectedStorage;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    /** Marks that the primary storage was not used, and why. */
    public void setFallback(String reason) {
        this.fallbackUsed = true;
        this.fallbackReason = reason;
    }

    public String getExplanationRo() {
        return explanationRo;
    }

    public void setExplanationRo(String explanationRo) {
        this.explanationRo = explanationRo;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
