package ro.filegrid.incident;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.incident.Incidents.Certainty;
import ro.filegrid.incident.Incidents.IncidentStatus;
import ro.filegrid.incident.Incidents.IncidentType;
import ro.filegrid.incident.Incidents.NewIncident;
import ro.filegrid.incident.Incidents.Severity;
import ro.filegrid.incident.Incidents.Source;

/**
 * Alertmanager webhook receiver: a firing business alert opens (or updates) an
 * incident, a resolved one resolves it. Labels {@code incident_type} and
 * {@code severity} select the incident; the alert fingerprint deduplicates it.
 */
@RestController
@Profile("api")
public class AlertmanagerWebhookController {

    private final IncidentService incidents;

    public AlertmanagerWebhookController(IncidentService incidents) {
        this.incidents = incidents;
    }

    public record Alert(String status, Map<String, String> labels, Map<String, String> annotations,
                        String fingerprint) {
    }

    public record Payload(List<Alert> alerts) {
    }

    @PostMapping("/incidents/alertmanager")
    public Map<String, Object> receive(@RequestBody Payload payload,
                                       @org.springframework.web.bind.annotation.RequestHeader(
                                               value = "X-Forwarded-For", required = false) String forwardedFor) {
        if (forwardedFor != null) {
            // Only Alertmanager, inside the Docker network, may call this; the gateway always adds the header.
            throw new ro.filegrid.passport.ProblemException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "internal-only", "Endpoint intern.");
        }
        int opened = 0;
        int resolved = 0;
        for (Alert alert : payload.alerts() == null ? List.<Alert>of() : payload.alerts()) {
            Map<String, String> labels = alert.labels() == null ? Map.of() : alert.labels();
            Map<String, String> ann = alert.annotations() == null ? Map.of() : alert.annotations();
            String fingerprint = "alertmanager:" + labels.getOrDefault("alertname", "alert") + ":"
                    + (alert.fingerprint() == null ? "" : alert.fingerprint());
            String typeLabel = labels.getOrDefault("incident_type", "");
            if ("INTEGRITY_MISMATCH".equalsIgnoreCase(typeLabel)) {
                continue; // one incident per affected file is opened by IncidentAutomation
            }
            if ("STORAGE_UNAVAILABLE".equalsIgnoreCase(typeLabel) && labels.get("storage") != null) {
                // Same fingerprint as IncidentAutomation: one incident per storage outage.
                fingerprint = "storage-unavailable:" + labels.get("storage").toUpperCase(Locale.ROOT);
            }
            if ("resolved".equalsIgnoreCase(alert.status())) {
                UUID open = incidents.openIncidentFor(fingerprint);
                if (open != null && incidents.summary(open).status().allowedTransitions()
                        .contains(IncidentStatus.RESOLVED)) {
                    incidents.changeStatus(open, IncidentStatus.RESOLVED,
                            ann.getOrDefault("summary", labels.getOrDefault("alertname", "Alertă rezolvată")),
                            "alertmanager");
                    resolved++;
                }
                continue;
            }
            IncidentType type = parse(IncidentType.class, labels.get("incident_type"), IncidentType.BUSINESS_SLA_AT_RISK);
            Severity severity = parse(Severity.class, labels.get("severity"), Severity.HIGH);
            incidents.open(new NewIncident(type, severity, Certainty.CONFIRMED, Source.ALERTMANAGER,
                    labels.get("zone"), labels.get("station"), labels.getOrDefault("storage", labels.get("category")),
                    ann.get("summary"), ann.get("description"), DataOrigin.REAL, fingerprint, List.of(),
                    "alertmanager"), Map.of("resource", labels.getOrDefault("alertname", "")));
            opened++;
        }
        return Map.of("opened", opened, "resolved", resolved);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
