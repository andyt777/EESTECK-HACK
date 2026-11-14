package ro.filegrid.incident;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.IntegrityStatus;

/** Vocabulary and views of the Security &amp; Incident Center. */
public final class Incidents {

    private Incidents() {
    }

    public enum IncidentType {
        UNAUTHORIZED_ACCESS, DIRECT_STORAGE_ACCESS_ATTEMPT, INTEGRITY_MISMATCH, STORAGE_UNAVAILABLE,
        TRANSFER_FAILED, BUSINESS_SLA_AT_RISK, GRID_FAULT_MAJOR, FIRE_REPORTED, FIELD_EMERGENCY, CYBER_SECURITY,
        GRID_ANOMALY;

        /** Types a person may report by hand. */
        public boolean manual() {
            return this == GRID_FAULT_MAJOR || this == FIRE_REPORTED || this == FIELD_EMERGENCY
                    || this == CYBER_SECURITY || this == GRID_ANOMALY;
        }
    }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public enum IncidentStatus {
        NEW, NOTIFIED, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, CLOSED;

        public boolean open() {
            return this != RESOLVED && this != CLOSED;
        }

        public List<IncidentStatus> allowedTransitions() {
            return switch (this) {
                case NEW, NOTIFIED -> List.of(ACKNOWLEDGED, IN_PROGRESS, RESOLVED);
                case ACKNOWLEDGED -> List.of(IN_PROGRESS, RESOLVED);
                case IN_PROGRESS -> List.of(RESOLVED);
                case RESOLVED -> List.of(CLOSED, IN_PROGRESS);
                case CLOSED -> List.of();
            };
        }
    }

    public enum Source { ALERTMANAGER, SYSTEM, USER, SENSOR }

    public enum Certainty { CONFIRMED, REPORTED, SUSPECTED }

    public enum NotificationStatus { PREPARED, SIMULATED, SENT, DELIVERED, CONFIRMED, ESCALATED, CLOSED }

    public enum NotifyMode { SIMULATED, PREPARED_ONLY, REAL }

    /** What is needed to open an incident. */
    public record NewIncident(IncidentType type, Severity severity, Certainty certainty, Source source, String zone,
                              String station, String asset, String title, String description,
                              DataOrigin dataOrigin, String fingerprint, List<UUID> fileIds, String actor) {
    }

    public record IncidentSummary(UUID id, String code, IncidentType type, String typeLabel, Severity severity,
                                  String severityLabel, IncidentStatus status, String statusLabel,
                                  Certainty certainty, Source source, String zone, String station, String asset,
                                  String title, String description, String owner, Instant createdAt,
                                  Instant updatedAt, Instant resolvedAt, String resolution, long fileCount,
                                  long notificationCount, long unconfirmedCount, DataOrigin dataOrigin) {
    }

    public record IncidentFile(UUID fileId, String name, FileCategory category, IntegrityStatus integrityStatus) {
    }

    public record TimelineEntry(long id, Instant at, String actor, String kind, String message) {
    }

    public record Notification(UUID id, UUID incidentId, String incidentCode, String kind, String recipientName,
                               String role, String roleLabel, String phoneMasked, String channel, NotifyMode mode,
                               NotificationStatus status, String statusLabel, int level, String body,
                               Instant createdAt, Instant sentAt, Instant deliveredAt, Instant confirmedAt,
                               String confirmedBy, Instant escalatedAt, Instant escalationDueAt) {
    }

    public record IncidentDetail(IncidentSummary incident, List<IncidentFile> files, List<TimelineEntry> timeline,
                                 List<Notification> notifications, List<IncidentStatus> allowedTransitions) {
    }

    /** Pushed on {@code /api/incidents/stream}. */
    public record IncidentStreamEvent(String kind, IncidentSummary incident, Notification notification) {
    }

    public record PreviewMessage(String role, String roleLabel, String recipientName, String body) {
    }
}
