package ro.filegrid.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Operational context of a file. Every field is optional; only what applies is filled in. */
@Entity
@Table(name = "file_context")
public class FileContext {

    @Id
    @Column(name = "file_id")
    private UUID fileId;

    @Column(length = 128)
    private String zone;

    @Column(length = 128)
    private String station;

    @Column(length = 128)
    private String equipment;

    @Column(name = "meter_id", length = 64)
    private String meterId;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(name = "event_time")
    private Instant eventTime;

    @Column(columnDefinition = "text")
    private String description;

    protected FileContext() {
        // for JPA
    }

    public FileContext(UUID fileId) {
        this.fileId = fileId;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getStation() {
        return station;
    }

    public void setStation(String station) {
        this.station = station;
    }

    public String getEquipment() {
        return equipment;
    }

    public void setEquipment(String equipment) {
        this.equipment = equipment;
    }

    public String getMeterId() {
        return meterId;
    }

    public void setMeterId(String meterId) {
        this.meterId = meterId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public void setEventTime(Instant eventTime) {
        this.eventTime = eventTime;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
