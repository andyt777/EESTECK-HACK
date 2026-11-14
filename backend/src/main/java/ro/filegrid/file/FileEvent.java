package ro.filegrid.file;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One entry of a file's audit trail. Append-only: there are no setters, and the database rejects updates. */
@Entity
@Table(name = "file_event")
public class FileEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private FileEventType type;

    @Column(updatable = false)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode details;

    @Column(name = "trace_id", updatable = false, length = 64)
    private String traceId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    protected FileEvent() {
        // for JPA
    }

    public FileEvent(UUID fileId, FileEventType type, String actor, JsonNode details, String traceId, Instant at) {
        this.fileId = fileId;
        this.type = type;
        this.actor = actor;
        this.details = details;
        this.traceId = traceId;
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public UUID getFileId() {
        return fileId;
    }

    public FileEventType getType() {
        return type;
    }

    public String getActor() {
        return actor;
    }

    public JsonNode getDetails() {
        return details;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getAt() {
        return at;
    }
}
