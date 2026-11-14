package ro.filegrid.incident;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.incident.Incidents.Certainty;
import ro.filegrid.incident.Incidents.IncidentStatus;
import ro.filegrid.incident.Incidents.IncidentType;
import ro.filegrid.incident.Incidents.NewIncident;
import ro.filegrid.incident.Incidents.Severity;
import ro.filegrid.incident.Incidents.Source;
import ro.filegrid.text.Texts;

/**
 * Opens incidents for conditions FileGrid observes itself (storage down,
 * integrity mismatch, failed transfer), resolves storage incidents when the
 * storage is back, and escalates unconfirmed notifications. Every detector
 * reads real state from meta-db, so it also covers events written by workers.
 */
@Component
@Profile("api")
public class IncidentAutomation {

    private static final Logger log = LoggerFactory.getLogger(IncidentAutomation.class);
    private static final String ACTOR = "filegrid";

    private final IncidentService incidents;
    private final NamedParameterJdbcTemplate jdbc;
    private final Texts texts;

    public IncidentAutomation(IncidentService incidents, NamedParameterJdbcTemplate jdbc, Texts texts) {
        this.incidents = incidents;
        this.jdbc = jdbc;
        this.texts = texts;
    }

    @Scheduled(initialDelay = 20_000, fixedDelayString = "${filegrid.incident.escalation-check:15000}")
    public void escalate() {
        try {
            int n = incidents.escalateOverdue();
            if (n > 0) {
                log.info("Escalated {} unconfirmed notification(s)", n);
            }
        } catch (RuntimeException e) {
            log.warn("Escalation check failed: {}", e.getMessage());
        }
    }

    @Scheduled(initialDelay = 20_000, fixedDelayString = "${filegrid.incident.detect-interval:10000}")
    public void detect() {
        safe("storage", this::storage);
        safe("integrity", this::integrity);
        safe("transfer", this::transferFailed);
    }

    private void safe(String name, Runnable detector) {
        try {
            detector.run();
        } catch (RuntimeException e) {
            log.warn("Incident detector {} failed: {}", name, e.getMessage());
        }
    }

    void storage() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT storage, status, detail FROM storage_health",
                new MapSqlParameterSource());
        for (Map<String, Object> row : rows) {
            String storage = (String) row.get("storage");
            String fingerprint = "storage-unavailable:" + storage;
            UUID open = incidents.openIncidentFor(fingerprint);
            if ("DOWN".equals(row.get("status"))) {
                if (open != null) {
                    continue;
                }
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("storage_name", texts.get("storage." + storage));
                attributes.put("resource", texts.get("storage." + storage));
                attributes.put("affected_operations", texts.get("storage.affected." + storage));
                attributes.put("current_status", texts.get("storage.status.down"));
                incidents.open(new NewIncident(IncidentType.STORAGE_UNAVAILABLE, Severity.HIGH,
                        Certainty.CONFIRMED, Source.SYSTEM, null, null, texts.get("storage." + storage), null,
                        (String) row.get("detail"), DataOrigin.REAL, fingerprint, List.of(), ACTOR), attributes);
            } else if (open != null) {
                IncidentStatus status = incidents.summary(open).status();
                if (status.allowedTransitions().contains(IncidentStatus.RESOLVED)) {
                    incidents.changeStatus(open, IncidentStatus.RESOLVED,
                            texts.get("incident.timeline.autoResolved", texts.get("storage." + storage) + " — "
                                    + texts.get("storage.status.up")), ACTOR);
                }
            }
        }
    }

    void integrity() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT ON (e.file_id) e.file_id, f.original_name, f.category, e.details->>'storage' AS storage
                  FROM file_event e JOIN file_record f ON f.id = e.file_id
                 WHERE e.type = 'INTEGRITY_INCIDENT' AND e.at > now() - interval '1 day'
                   AND NOT EXISTS (SELECT 1 FROM incident_file x JOIN incident i ON i.id = x.incident_id
                                    WHERE x.file_id = e.file_id AND i.type = 'INTEGRITY_MISMATCH'
                                      AND i.created_at >= e.at - interval '1 minute')
                 ORDER BY e.file_id, e.at DESC
                 LIMIT 20
                """, new MapSqlParameterSource());
        for (Map<String, Object> row : rows) {
            UUID fileId = (UUID) row.get("file_id");
            String storage = (String) row.get("storage");
            String storageName = storage == null ? "" : texts.get("storage." + storage);
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("file_name", (String) row.get("original_name"));
            attributes.put("file_id", fileId.toString());
            attributes.put("storage_name", storageName);
            attributes.put("category", texts.get("category." + row.get("category")));
            attributes.put("resource", (String) row.get("original_name"));
            incidents.open(new NewIncident(IncidentType.INTEGRITY_MISMATCH, Severity.HIGH,
                    Certainty.CONFIRMED, Source.SYSTEM, null, null, storageName, null,
                    texts.get("integrity.mismatch"), DataOrigin.REAL, "integrity:" + fileId, List.of(fileId), ACTOR), attributes);
        }
    }

    void transferFailed() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT f.id, f.original_name, f.category, f.data_origin FROM file_record f
                 WHERE f.status = 'TRANSFER_FAILED' AND f.received_at > now() - interval '1 day'
                   AND NOT EXISTS (SELECT 1 FROM incident_file x JOIN incident i ON i.id = x.incident_id
                                    WHERE x.file_id = f.id AND i.type = 'TRANSFER_FAILED')
                 LIMIT 20
                """, new MapSqlParameterSource());
        for (Map<String, Object> row : rows) {
            UUID fileId = (UUID) row.get("id");
            incidents.open(new NewIncident(IncidentType.TRANSFER_FAILED, Severity.MEDIUM,
                    Certainty.CONFIRMED, Source.SYSTEM, null, null, (String) row.get("original_name"), null,
                    texts.get("status.TRANSFER_FAILED"), DataOrigin.valueOf((String) row.get("data_origin")),
                    "transfer-failed:" + fileId, List.of(fileId), ACTOR), Map.of("resource", (String) row.get("original_name"),
                    "file_name", (String) row.get("original_name"), "file_id", fileId.toString()));
        }
    }
}
