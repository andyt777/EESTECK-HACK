package ro.filegrid.incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.incident.IncidentService.Page;
import ro.filegrid.incident.Incidents.Certainty;
import ro.filegrid.incident.Incidents.IncidentDetail;
import ro.filegrid.incident.Incidents.IncidentStatus;
import ro.filegrid.incident.Incidents.IncidentSummary;
import ro.filegrid.incident.Incidents.IncidentType;
import ro.filegrid.incident.Incidents.NewIncident;
import ro.filegrid.incident.Incidents.Notification;
import ro.filegrid.incident.Incidents.NotificationStatus;
import ro.filegrid.incident.Incidents.NotifyMode;
import ro.filegrid.incident.Incidents.PreviewMessage;
import ro.filegrid.incident.Incidents.Severity;
import ro.filegrid.incident.Incidents.Source;
import ro.filegrid.passport.ProblemException;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.text.Texts;

/** REST API of the Security &amp; Incident Center. */
@RestController
@Profile("api")
@RequestMapping("/incidents")
public class IncidentController {

    private final IncidentService incidents;
    private final IncidentHub hub;
    private final CurrentActorProvider actors;
    private final AccessPolicy access;
    private final Texts texts;

    public IncidentController(IncidentService incidents, IncidentHub hub, CurrentActorProvider actors,
                              AccessPolicy access, Texts texts) {
        this.incidents = incidents;
        this.hub = hub;
        this.actors = actors;
        this.access = access;
        this.texts = texts;
    }

    /** Body of POST /api/incidents and /preview. */
    public record IncidentRequest(IncidentType type, Severity severity, Certainty certainty, Source source,
                                  String zone, String station, String asset, String title, String description,
                                  DataOrigin dataOrigin, String fingerprint, List<UUID> fileIds) {
    }

    public record NoteRequest(String note) {
    }

    public record AssignRequest(String owner) {
    }

    public record StatusRequest(IncidentStatus status, String note) {
    }

    public record ConfirmRequest(String confirmedBy) {
    }

    @GetMapping
    public Page<IncidentSummary> list(@RequestParam(required = false) IncidentType type,
                                      @RequestParam(required = false) Severity severity,
                                      @RequestParam(required = false) IncidentStatus status,
                                      @RequestParam(required = false) String zone,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to,
                                      @RequestParam(required = false) Boolean open,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        authorize("incident:read");
        return incidents.list(type, severity, status, zone, from, to, open, page, size);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        authorize("incident:read");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", incidents.mode());
        out.put("modeLabel", texts.get("notification.mode." + incidents.mode().name()));
        out.put("realChannelConfigured", incidents.realChannelConfigured());
        out.put("escalationSeconds", incidents.escalationSeconds());
        List<Map<String, Object>> types = new ArrayList<>();
        for (IncidentType t : IncidentType.values()) {
            types.add(Map.of("type", t, "label", texts.get("incident.type." + t.name()), "manual", t.manual()));
        }
        out.put("types", types);
        List<Map<String, Object>> severities = new ArrayList<>();
        for (Severity s : Severity.values()) {
            severities.add(Map.of("value", s, "label", texts.get("incident.severity." + s.name())));
        }
        out.put("severities", severities);
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (IncidentStatus s : IncidentStatus.values()) {
            statuses.add(Map.of("value", s, "label", texts.get("incident.status." + s.name())));
        }
        out.put("statuses", statuses);
        return out;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        authorize("incident:read");
        // The stream ends when the caller's token expires; the client reconnects with a fresh one.
        return hub.subscribe(ro.filegrid.security.TokenLifetime.remaining(java.time.Duration.ofMinutes(30)));
    }

    @GetMapping("/{id}")
    public IncidentDetail detail(@PathVariable UUID id) {
        authorize("incident:read");
        return incidents.detail(id, access.isAllowed(actors.currentActor(), "pii:read"));
    }

    @PostMapping
    public IncidentDetail open(@RequestBody IncidentRequest body) {
        authorize("incident:create");
        return incidents.open(toNew(body));
    }

    @PostMapping("/preview")
    public List<PreviewMessage> preview(@RequestBody IncidentRequest body) {
        authorize("incident:read");
        return incidents.preview(toNew(body));
    }

    @PostMapping("/{id}/acknowledge")
    public IncidentDetail acknowledge(@PathVariable UUID id, @RequestBody(required = false) NoteRequest body) {
        authorize("incident:write");
        return incidents.acknowledge(id, actors.currentActor(), body == null ? null : body.note());
    }

    @PostMapping("/{id}/assign")
    public IncidentDetail assign(@PathVariable UUID id, @RequestBody AssignRequest body) {
        authorize("incident:write");
        return incidents.assign(id, body.owner(), actors.currentActor());
    }

    @PostMapping("/{id}/status")
    public IncidentDetail status(@PathVariable UUID id, @RequestBody StatusRequest body) {
        authorize("incident:write");
        if (body.status() == null) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "status-required",
                    texts.get("incident.error.transition", "?", "?"));
        }
        return incidents.changeStatus(id, body.status(), body.note(), actors.currentActor());
    }

    @GetMapping("/notifications")
    public Page<Notification> notifications(@RequestParam(required = false) NotifyMode mode,
                                            @RequestParam(required = false) NotificationStatus status,
                                            @RequestParam(required = false) UUID incidentId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        authorize("incident:read");
        return incidents.notifications(mode, status, incidentId, page, size);
    }

    @PostMapping("/notifications/{id}/confirm")
    public Notification confirm(@PathVariable UUID id, @RequestBody(required = false) ConfirmRequest body) {
        authorize("incident:confirm");
        return incidents.confirm(id, body == null ? null : body.confirmedBy());
    }

    private NewIncident toNew(IncidentRequest b) {
        if (b == null || b.type() == null) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "type-required", texts.get("incident.error.type"));
        }
        Source source = b.source() == null ? Source.USER : b.source();
        if (source == Source.USER && !b.type().manual()) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "type-not-manual", texts.get("incident.error.manualType"));
        }
        return new NewIncident(b.type(), b.severity() == null ? Severity.MEDIUM : b.severity(),
                b.certainty() == null ? Certainty.REPORTED : b.certainty(), source, b.zone(), b.station(), b.asset(),
                b.title(), b.description(), b.dataOrigin() == null ? DataOrigin.REAL : b.dataOrigin(),
                b.fingerprint(), b.fileIds(), actors.currentActor());
    }

    private void authorize(String permission) {
        if (!access.isAllowed(actors.currentActor(), permission)) {
            throw new ProblemException(HttpStatus.FORBIDDEN, "forbidden", texts.get("file.error.forbidden"));
        }
    }
}
