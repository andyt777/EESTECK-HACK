package ro.filegrid.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.IntegrityStatus;
import ro.filegrid.incident.Incidents.Certainty;
import ro.filegrid.incident.Incidents.IncidentDetail;
import ro.filegrid.incident.Incidents.IncidentFile;
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
import ro.filegrid.incident.Incidents.TimelineEntry;
import ro.filegrid.passport.ProblemException;
import ro.filegrid.text.Texts;

/**
 * Security &amp; Incident Center: incidents, their notifications, confirmation,
 * escalation and resolution. Every change is written to the incident timeline
 * and pushed live on {@code /api/incidents/stream}.
 */
@Service
@Profile("api")
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT)
            .withZone(ZoneId.of("Europe/Bucharest"));
    private static final Set<NotificationStatus> AWAITING_CONFIRMATION = Set.of(NotificationStatus.PREPARED,
            NotificationStatus.SIMULATED, NotificationStatus.SENT, NotificationStatus.DELIVERED);

    private static final String SUMMARY_SQL = """
            SELECT i.*,
                   (SELECT count(*) FROM incident_file f WHERE f.incident_id = i.id) AS file_count,
                   (SELECT count(*) FROM notification n WHERE n.incident_id = i.id) AS notification_count,
                   (SELECT count(*) FROM notification n WHERE n.incident_id = i.id AND n.kind = 'ALERT'
                       AND n.status IN ('PREPARED', 'SIMULATED', 'SENT', 'DELIVERED', 'ESCALATED')) AS unconfirmed_count
              FROM incident i
            """;
    private static final String NOTIFICATION_SQL = """
            SELECT n.*, i.code AS incident_code FROM notification n JOIN incident i ON i.id = n.incident_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationRouting routing;
    private final MessageRenderer renderer;
    private final NotificationChannel channel;
    private final IncidentHub hub;
    private final Texts texts;
    private final String configuredMode;

    public IncidentService(NamedParameterJdbcTemplate jdbc, NotificationRouting routing, MessageRenderer renderer,
                           NotificationChannel channel, IncidentHub hub, Texts texts,
                           @Value("${filegrid.notify.mode:SIMULATED}") String configuredMode) {
        this.jdbc = jdbc;
        this.routing = routing;
        this.renderer = renderer;
        this.channel = channel;
        this.hub = hub;
        this.texts = texts;
        this.configuredMode = configuredMode;
    }

    public NotifyMode mode() {
        try {
            return NotifyMode.valueOf(configuredMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NotifyMode.SIMULATED;
        }
    }

    public boolean realChannelConfigured() {
        return channel.configured();
    }

    public Map<String, Long> escalationSeconds() {
        return routing.escalationSeconds();
    }

    // ------------------------------------------------------------------ create

    /**
     * Opens an incident and notifies level 1. When an open incident with the same
     * fingerprint exists, that one is updated instead (no duplicate incident).
     */
    @Transactional
    public IncidentDetail open(NewIncident request) {
        if (request.type() == IncidentType.FIRE_REPORTED
                && (request.source() != Source.USER && request.source() != Source.SENSOR)) {
            // A fire is never inferred by FileGrid: only a person or a sensor can report it.
            throw new ProblemException(HttpStatus.BAD_REQUEST, "fire-source", texts.get("incident.error.fireSource"));
        }
        if (request.fingerprint() != null) {
            UUID existing = findOpenByFingerprint(request.fingerprint());
            if (existing != null) {
                linkFiles(existing, request.fileIds());
                timeline(existing, request.actor(), "REPEATED", texts.get("incident.timeline.repeated"));
                touch(existing);
                publish("INCIDENT_UPDATED", existing, null);
                return detail(existing);
            }
        }
        UUID id = UUID.randomUUID();
        long seq = jdbc.getJdbcTemplate().queryForObject("SELECT nextval('incident_code_seq')", Long.class);
        String code = String.format(Locale.ROOT, "INC-%d-%06d", Instant.now().atZone(ZoneId.of("Europe/Bucharest"))
                .getYear(), seq);
        String title = request.title() != null && !request.title().isBlank() ? request.title()
                : defaultTitle(request.type(), request.certainty());
        try {
            jdbc.update("""
                    INSERT INTO incident (id, code, type, severity, status, certainty, source, zone, station, asset,
                                          title, description, data_origin, fingerprint, created_by)
                    VALUES (:id, :code, :type, :severity, 'NEW', :certainty, :source, :zone, :station, :asset,
                            :title, :description, :origin, :fingerprint, :actor)
                    """, new MapSqlParameterSource().addValue("id", id).addValue("code", code)
                    .addValue("type", request.type().name()).addValue("severity", request.severity().name())
                    .addValue("certainty", request.certainty().name()).addValue("source", request.source().name())
                    .addValue("zone", blankToNull(request.zone())).addValue("station", blankToNull(request.station()))
                    .addValue("asset", blankToNull(request.asset())).addValue("title", title)
                    .addValue("description", blankToNull(request.description()))
                    .addValue("origin", (request.dataOrigin() == null ? DataOrigin.REAL : request.dataOrigin()).name())
                    .addValue("fingerprint", request.fingerprint()).addValue("actor", request.actor()));
        } catch (DuplicateKeyException race) {
            UUID existing = findOpenByFingerprint(request.fingerprint());
            if (existing != null) {
                return detail(existing);
            }
            throw race;
        }
        linkFiles(id, request.fileIds());
        timeline(id, request.actor(), "CREATED", texts.get("incident.timeline.created",
                texts.get("incident.source." + request.source().name()),
                texts.get("incident.certainty." + request.certainty().name())));
        log.info("Incident {} opened: {} {} ({})", code, request.severity(), request.type(), request.source());
        publish("INCIDENT_CREATED", id, null);
        notifyLevel(id, 1, request.actor());
        return detail(id);
    }

    private UUID findOpenByFingerprint(String fingerprint) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM incident WHERE fingerprint = :fp AND status NOT IN ('RESOLVED', 'CLOSED')
                """, new MapSqlParameterSource("fp", fingerprint), UUID.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** The open incident with this fingerprint, if any. */
    public UUID openIncidentFor(String fingerprint) {
        return findOpenByFingerprint(fingerprint);
    }

    private void linkFiles(UUID incidentId, List<UUID> fileIds) {
        if (fileIds == null) {
            return;
        }
        for (UUID fileId : new LinkedHashSet<>(fileIds)) {
            jdbc.update("""
                    INSERT INTO incident_file (incident_id, file_id)
                    SELECT :incident, id FROM file_record WHERE id = :file
                    ON CONFLICT DO NOTHING
                    """, new MapSqlParameterSource("incident", incidentId).addValue("file", fileId));
        }
    }

    private String defaultTitle(IncidentType type, Certainty certainty) {
        if (type == IncidentType.FIRE_REPORTED && certainty == Certainty.SUSPECTED) {
            return texts.get("incident.title.FIRE_SUSPECTED");
        }
        return texts.get("incident.title." + type.name());
    }

    // ------------------------------------------------------------ notification

    /** Renders the messages a new incident would send, without saving or sending anything. */
    public List<PreviewMessage> preview(NewIncident request) {
        NotificationRouting.Rule rule = routing.ruleFor(request.type());
        if (rule == null || rule.levels().isEmpty()) {
            return List.of();
        }
        Map<String, String> values = values(null, request.type(), request.severity(), request.certainty(),
                request.source(), request.zone(), request.station(), request.asset(), request.description(),
                request.fileIds() == null ? 0 : request.fileIds().size(), Map.of(), Instant.now(), "INC-…");
        List<PreviewMessage> out = new ArrayList<>();
        for (String role : rule.levels().get(0)) {
            NotificationRouting.Contact contact = routing.contact(role, request.zone());
            out.add(new PreviewMessage(role, contact.roleLabel(), contact.name(),
                    renderer.render(rule.template(), values)));
        }
        return out;
    }

    /** Sends (or simulates) the alert to every role of one escalation level. */
    void notifyLevel(UUID incidentId, int level, String actor) {
        IncidentRow incident = row(incidentId);
        NotificationRouting.Rule rule = routing.ruleFor(incident.type);
        if (rule == null || rule.levels().isEmpty()) {
            timeline(incidentId, "filegrid", "NOT_NOTIFIED", texts.get("incident.timeline.noRule"));
            return;
        }
        if (incident.severity.ordinal() < rule.minSeverity().ordinal()) {
            timeline(incidentId, "filegrid", "NOT_NOTIFIED", texts.get("incident.timeline.belowThreshold",
                    texts.get("incident.severity." + rule.minSeverity().name())));
            return;
        }
        if (level > rule.levels().size()) {
            return;
        }
        Map<String, String> values = values(incident);
        String body = null;
        List<String> recipients = new ArrayList<>();
        Instant due = Instant.now().plus(routing.escalationAfter(incident.severity));
        for (String role : rule.levels().get(level - 1)) {
            NotificationRouting.Contact contact = routing.contact(role, incident.zone);
            body = renderer.render(rule.template(), values);
            UUID notificationId = deliver(incident, contact, "ALERT", level, body, due);
            recipients.add(contact.name());
            publish("NOTIFICATION", incidentId, notificationId);
        }
        timeline(incidentId, actor, "NOTIFIED", texts.get("incident.timeline.notified", level,
                String.join(", ", recipients), texts.get("notification.mode." + mode().name())));
        if (incident.status == IncidentStatus.NEW) {
            setStatus(incidentId, IncidentStatus.NOTIFIED);
        }
        publish("INCIDENT_UPDATED", incidentId, null);
    }

    /** Stores one notification and hands it to the channel according to the mode. */
    private UUID deliver(IncidentRow incident, NotificationRouting.Contact contact, String kind, int level,
                         String body, Instant escalationDue) {
        UUID id = UUID.randomUUID();
        NotifyMode mode = mode();
        NotificationStatus status = switch (mode) {
            case SIMULATED -> NotificationStatus.SIMULATED;
            case PREPARED_ONLY -> NotificationStatus.PREPARED;
            case REAL -> NotificationStatus.PREPARED;
        };
        String providerId = null;
        Instant sentAt = null;
        if (mode == NotifyMode.REAL) {
            if (!channel.configured() || contact.phone() == null) {
                timeline(incident.id, "filegrid", "NOTIFICATION_FAILED",
                        texts.get("incident.timeline.notConfigured", contact.name()));
            } else {
                try {
                    providerId = channel.send(contact.phone(), body);
                    status = NotificationStatus.SENT;
                    sentAt = Instant.now();
                } catch (RuntimeException e) {
                    timeline(incident.id, "filegrid", "NOTIFICATION_FAILED",
                            texts.get("incident.timeline.sendFailed", contact.name()));
                }
            }
        } else if (mode == NotifyMode.SIMULATED) {
            sentAt = Instant.now();
        }
        jdbc.update("""
                INSERT INTO notification (id, incident_id, kind, recipient_name, role, phone_masked, mode, status,
                                          level, body, provider_id, sent_at, escalation_due_at)
                VALUES (:id, :incident, :kind, :name, :role, :phone, :mode, :status, :level, :body, :provider,
                        :sentAt, :due)
                """, new MapSqlParameterSource().addValue("id", id).addValue("incident", incident.id)
                .addValue("kind", kind).addValue("name", contact.name()).addValue("role", contact.role())
                .addValue("phone", contact.masked()).addValue("mode", mode.name()).addValue("status", status.name())
                .addValue("level", level).addValue("body", body).addValue("provider", providerId)
                .addValue("sentAt", ts(sentAt)).addValue("due", "ALERT".equals(kind) ? ts(escalationDue) : null));
        return id;
    }

    private Map<String, String> values(IncidentRow i) {
        return values(i.id, i.type, i.severity, i.certainty, i.source, i.zone, i.station, i.asset, i.description,
                fileCount(i.id), attributes(i.id), i.createdAt, i.code);
    }

    private Map<String, String> values(UUID id, IncidentType type, Severity severity, Certainty certainty,
                                       Source source, String zone, String station, String asset, String description,
                                       long files, Map<String, String> attributes, Instant at, String code) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("incident_id", code);
        v.put("zone", zone);
        String stationAsset = join(" / ", station, asset);
        v.put("asset", stationAsset);
        v.put("station_or_asset", stationAsset);
        v.put("location", join(", ", zone, station));
        v.put("location_or_asset", join(" — ", zone, stationAsset));
        v.put("timestamp", WHEN.format(at));
        v.put("description", description);
        v.put("file_count", files > 0 ? String.valueOf(files) : null);
        v.put("source", texts.get("incident.source." + source.name()));
        v.put("event_type", texts.get("incident.type." + type.name()));
        v.put("resource", attributes.getOrDefault("resource", stationAsset));
        v.put("severity", texts.get("incident.severity." + severity.name()));
        if (type == IncidentType.FIRE_REPORTED) {
            String key = certainty == Certainty.SUSPECTED ? "SUSPECTED" : certainty == Certainty.CONFIRMED
                    ? "CONFIRMED" : "REPORTED";
            v.put("title", texts.get("message.fire.title." + key));
            v.put("headline", texts.get("message.fire.headline." + key));
        } else {
            v.put("headline", texts.get("message.headline." + type.name()));
        }
        v.putAll(attributes);
        return v;
    }

    private static String join(String separator, String... parts) {
        List<String> present = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                present.add(p);
            }
        }
        return present.isEmpty() ? null : String.join(separator, present);
    }

    // ------------------------------------------------------- confirm, escalate

    /** "OK" from a recipient (UI button, WhatsApp reply). Reading a message never counts as confirmation. */
    @Transactional
    public Notification confirm(UUID notificationId, String confirmedBy) {
        Notification n = notification(notificationId);
        if (n.status() == NotificationStatus.CONFIRMED) {
            return n;
        }
        if (n.status() == NotificationStatus.CLOSED || !"ALERT".equals(n.kind())) {
            throw new ProblemException(HttpStatus.CONFLICT, "notification-closed",
                    texts.get("incident.error.notificationClosed"));
        }
        String who = confirmedBy == null || confirmedBy.isBlank() ? n.recipientName() : confirmedBy;
        jdbc.update("""
                UPDATE notification SET status = 'CONFIRMED', confirmed_at = now(), confirmed_by = :who,
                                        escalation_due_at = NULL
                 WHERE id = :id
                """, new MapSqlParameterSource("id", notificationId).addValue("who", who));
        timeline(n.incidentId(), who, "CONFIRMED", texts.get("incident.timeline.confirmed", n.recipientName(),
                n.roleLabel()));
        IncidentRow incident = row(n.incidentId());
        if (incident.status == IncidentStatus.NEW || incident.status == IncidentStatus.NOTIFIED) {
            setStatus(incident.id, IncidentStatus.ACKNOWLEDGED);
            if (incident.owner == null) {
                jdbc.update("UPDATE incident SET owner = :owner WHERE id = :id",
                        new MapSqlParameterSource("id", incident.id).addValue("owner", n.recipientName()));
            }
        }
        // The incident is taken over: nobody else needs to be chased for it.
        jdbc.update("""
                UPDATE notification SET escalation_due_at = NULL
                 WHERE incident_id = :id AND status IN ('PREPARED', 'SIMULATED', 'SENT', 'DELIVERED')
                """, new MapSqlParameterSource("id", n.incidentId()));
        touch(n.incidentId());
        publish("NOTIFICATION", n.incidentId(), notificationId);
        publish("INCIDENT_UPDATED", n.incidentId(), null);
        return notification(notificationId);
    }

    /** Delivery receipt from the provider (Twilio status callback). */
    @Transactional
    public void delivered(String providerId) {
        jdbc.update("""
                UPDATE notification SET status = 'DELIVERED', delivered_at = now()
                 WHERE provider_id = :pid AND status = 'SENT'
                """, new MapSqlParameterSource("pid", providerId));
    }

    /**
     * Notifications not confirmed in time: marked ESCALATED and the next level
     * of the chain is informed.
     */
    @Transactional
    public int escalateOverdue() {
        List<Map<String, Object>> overdue = jdbc.queryForList("""
                SELECT n.id, n.incident_id, n.level FROM notification n JOIN incident i ON i.id = n.incident_id
                 WHERE n.kind = 'ALERT' AND n.escalation_due_at < now()
                   AND n.status IN ('PREPARED', 'SIMULATED', 'SENT', 'DELIVERED')
                   AND i.status IN ('NEW', 'NOTIFIED')
                 ORDER BY n.escalation_due_at
                 FOR UPDATE OF n SKIP LOCKED
                """, new MapSqlParameterSource());
        Map<UUID, Integer> levels = new LinkedHashMap<>();
        for (Map<String, Object> row : overdue) {
            UUID id = (UUID) row.get("id");
            jdbc.update("""
                    UPDATE notification SET status = 'ESCALATED', escalated_at = now(), escalation_due_at = NULL
                     WHERE id = :id
                    """, new MapSqlParameterSource("id", id));
            levels.merge((UUID) row.get("incident_id"), ((Number) row.get("level")).intValue(), Math::max);
            publish("NOTIFICATION", (UUID) row.get("incident_id"), id);
        }
        for (Map.Entry<UUID, Integer> e : levels.entrySet()) {
            UUID incidentId = e.getKey();
            int next = e.getValue() + 1;
            IncidentRow incident = row(incidentId);
            NotificationRouting.Rule rule = routing.ruleFor(incident.type);
            long minutes = Math.max(1, routing.escalationAfter(incident.severity).toMinutes());
            boolean alreadyNotified = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT count(*) FROM notification WHERE incident_id = ? AND level = ? AND kind = 'ALERT'",
                    Long.class, incidentId, next) > 0;
            if (rule != null && next <= rule.levels().size() && !alreadyNotified) {
                timeline(incidentId, "filegrid", "ESCALATED", texts.get("incident.timeline.escalated", minutes, next));
                notifyLevel(incidentId, next, "filegrid");
            } else if (!alreadyNotified) {
                timeline(incidentId, "filegrid", "ESCALATED", texts.get("incident.timeline.escalatedLast", minutes));
            }
            touch(incidentId);
            publish("INCIDENT_UPDATED", incidentId, null);
        }
        return overdue.size();
    }

    // ----------------------------------------------------------- state changes

    @Transactional
    public IncidentDetail acknowledge(UUID id, String actor, String note) {
        IncidentRow incident = row(id);
        if (incident.status == IncidentStatus.NEW || incident.status == IncidentStatus.NOTIFIED) {
            setStatus(id, IncidentStatus.ACKNOWLEDGED);
        }
        if (incident.owner == null) {
            jdbc.update("UPDATE incident SET owner = :owner WHERE id = :id",
                    new MapSqlParameterSource("id", id).addValue("owner", actor));
        }
        jdbc.update("""
                UPDATE notification SET escalation_due_at = NULL
                 WHERE incident_id = :id AND status IN ('PREPARED', 'SIMULATED', 'SENT', 'DELIVERED')
                """, new MapSqlParameterSource("id", id));
        timeline(id, actor, "ACKNOWLEDGED", note == null || note.isBlank() ? texts.get("incident.timeline.acknowledged")
                : texts.get("incident.timeline.acknowledgedNote", note.strip()));
        touch(id);
        publish("INCIDENT_UPDATED", id, null);
        return detail(id);
    }

    @Transactional
    public IncidentDetail assign(UUID id, String owner, String actor) {
        if (owner == null || owner.isBlank()) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "owner-required", texts.get("incident.error.owner"));
        }
        row(id);
        jdbc.update("UPDATE incident SET owner = :owner WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("owner", owner.strip()));
        timeline(id, actor, "ASSIGNED", texts.get("incident.timeline.assigned", owner.strip()));
        touch(id);
        publish("INCIDENT_UPDATED", id, null);
        return detail(id);
    }

    @Transactional
    public IncidentDetail changeStatus(UUID id, IncidentStatus target, String note, String actor) {
        IncidentRow incident = row(id);
        if (target == incident.status) {
            return detail(id);
        }
        if (!incident.status.allowedTransitions().contains(target)) {
            throw new ProblemException(HttpStatus.CONFLICT, "transition-not-allowed",
                    texts.get("incident.error.transition", texts.get("incident.status." + incident.status.name()),
                            texts.get("incident.status." + target.name())));
        }
        if (target == IncidentStatus.RESOLVED && (note == null || note.isBlank())) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "resolution-required",
                    texts.get("incident.error.resolution"));
        }
        setStatus(id, target);
        timeline(id, actor, "STATUS", texts.get("incident.timeline.status",
                texts.get("incident.status." + target.name()))
                + (note == null || note.isBlank() ? "" : " — " + note.strip()));
        if (target == IncidentStatus.RESOLVED) {
            jdbc.update("UPDATE incident SET resolution = :note, resolved_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", id).addValue("note", note.strip()));
            closeOpenAlerts(id);
            sendUpdate(row(id), note.strip(), texts.get("message.update.resolved"), actor);
        } else if (target == IncidentStatus.CLOSED) {
            closeOpenAlerts(id);
        } else if (target == IncidentStatus.IN_PROGRESS) {
            jdbc.update("""
                    UPDATE notification SET escalation_due_at = NULL
                     WHERE incident_id = :id AND status IN ('PREPARED', 'SIMULATED', 'SENT', 'DELIVERED')
                    """, new MapSqlParameterSource("id", id));
        }
        touch(id);
        publish("INCIDENT_UPDATED", id, null);
        return detail(id);
    }

    private void closeOpenAlerts(UUID id) {
        jdbc.update("""
                UPDATE notification SET status = 'CLOSED', escalation_due_at = NULL
                 WHERE incident_id = :id AND kind = 'ALERT'
                   AND status IN ('PREPARED', 'SIMULATED', 'SENT', 'DELIVERED', 'ESCALATED')
                """, new MapSqlParameterSource("id", id));
    }

    /** §10.9: the final update goes to everyone who was alerted about the incident. */
    private void sendUpdate(IncidentRow incident, String update, String nextAction, String actor) {
        List<Map<String, Object>> recipients = jdbc.queryForList("""
                SELECT DISTINCT role FROM notification WHERE incident_id = :id AND kind = 'ALERT'
                """, new MapSqlParameterSource("id", incident.id));
        if (recipients.isEmpty()) {
            return;
        }
        Map<String, String> values = values(incident);
        values.put("status", texts.get("incident.status." + incident.status.name()));
        values.put("resolution_update", update);
        values.put("timestamp", WHEN.format(Instant.now()));
        values.put("next_action_or_closure", nextAction);
        String body = renderer.render("incident-update", values);
        List<String> names = new ArrayList<>();
        for (Map<String, Object> r : recipients) {
            NotificationRouting.Contact contact = routing.contact((String) r.get("role"), incident.zone);
            UUID nid = deliver(incident, contact, "UPDATE", 0, body, null);
            names.add(contact.name());
            publish("NOTIFICATION", incident.id, nid);
        }
        timeline(incident.id, actor, "UPDATE_SENT", texts.get("incident.timeline.updateSent", String.join(", ", names),
                texts.get("notification.mode." + mode().name())));
    }

    private void setStatus(UUID id, IncidentStatus status) {
        jdbc.update("UPDATE incident SET status = :status, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("status", status.name()));
    }

    private void touch(UUID id) {
        jdbc.update("UPDATE incident SET updated_at = now() WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public void timeline(UUID incidentId, String actor, String kind, String message) {
        jdbc.update("""
                INSERT INTO incident_event (incident_id, actor, kind, message) VALUES (:id, :actor, :kind, :message)
                """, new MapSqlParameterSource("id", incidentId).addValue("actor", actor).addValue("kind", kind)
                .addValue("message", message));
    }

    /** Stores extra template values (resource, file name, storage...) for an incident. */
    @Transactional
    public void putAttributes(UUID incidentId, Map<String, String> attributes) {
        try {
            jdbc.update("UPDATE incident SET attributes = attributes || CAST(:json AS jsonb) WHERE id = :id",
                    new MapSqlParameterSource("id", incidentId).addValue("json",
                            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(attributes)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ reads

    public record Page<T>(List<T> items, int page, int size, long total) {
    }

    public Page<IncidentSummary> list(IncidentType type, Severity severity, IncidentStatus status, String zone,
                                      Instant from, Instant to, Boolean open, int page, int size) {
        int pageSize = Math.max(1, Math.min(size, 200));
        int pageIndex = Math.max(0, page);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (type != null) {
            where.append(" AND i.type = :type");
            p.addValue("type", type.name());
        }
        if (severity != null) {
            where.append(" AND i.severity = :severity");
            p.addValue("severity", severity.name());
        }
        if (status != null) {
            where.append(" AND i.status = :status");
            p.addValue("status", status.name());
        }
        if (zone != null && !zone.isBlank()) {
            where.append(" AND (i.zone ILIKE :zone OR i.station ILIKE :zone)");
            p.addValue("zone", "%" + zone.strip() + "%");
        }
        if (from != null) {
            where.append(" AND i.created_at >= :from");
            p.addValue("from", ts(from));
        }
        if (to != null) {
            where.append(" AND i.created_at <= :to");
            p.addValue("to", ts(to));
        }
        if (open != null) {
            where.append(open ? " AND i.status NOT IN ('RESOLVED', 'CLOSED')" : " AND i.status IN ('RESOLVED', 'CLOSED')");
        }
        p.addValue("limit", pageSize).addValue("offset", (long) pageIndex * pageSize);
        List<IncidentSummary> items = jdbc.query(SUMMARY_SQL + where
                + " ORDER BY i.created_at DESC LIMIT :limit OFFSET :offset", p, this::summary);
        Long total = jdbc.queryForObject("SELECT count(*) FROM incident i" + where, p, Long.class);
        return new Page<>(items, pageIndex, pageSize, total == null ? 0 : total);
    }

    public IncidentSummary summary(UUID id) {
        List<IncidentSummary> list = jdbc.query(SUMMARY_SQL + " WHERE i.id = :id",
                new MapSqlParameterSource("id", id), this::summary);
        if (list.isEmpty()) {
            throw new ProblemException(HttpStatus.NOT_FOUND, "incident-not-found", texts.get("incident.error.notFound"));
        }
        return list.get(0);
    }

    public IncidentDetail detail(UUID id) {
        IncidentSummary summary = summary(id);
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<IncidentFile> files = jdbc.query("""
                SELECT f.id, f.original_name, f.category, f.integrity_status
                  FROM incident_file x JOIN file_record f ON f.id = x.file_id WHERE x.incident_id = :id
                 ORDER BY f.received_at
                """, p, (rs, i) -> new IncidentFile(rs.getObject("id", UUID.class), rs.getString("original_name"),
                FileCategory.valueOf(rs.getString("category")), IntegrityStatus.valueOf(rs.getString("integrity_status"))));
        List<TimelineEntry> timeline = jdbc.query("""
                SELECT id, at, actor, kind, message FROM incident_event WHERE incident_id = :id ORDER BY at, id
                """, p, (rs, i) -> new TimelineEntry(rs.getLong("id"), instant(rs, "at"), rs.getString("actor"),
                rs.getString("kind"), rs.getString("message")));
        List<Notification> notifications = jdbc.query(NOTIFICATION_SQL
                + " WHERE n.incident_id = :id ORDER BY n.created_at, n.level", p, this::notification);
        return new IncidentDetail(summary, files, timeline, notifications, summary.status().allowedTransitions());
    }

    public Page<Notification> notifications(NotifyMode mode, NotificationStatus status, UUID incidentId, int page,
                                            int size) {
        int pageSize = Math.max(1, Math.min(size, 200));
        int pageIndex = Math.max(0, page);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (mode != null) {
            where.append(" AND n.mode = :mode");
            p.addValue("mode", mode.name());
        }
        if (status != null) {
            where.append(" AND n.status = :status");
            p.addValue("status", status.name());
        }
        if (incidentId != null) {
            where.append(" AND n.incident_id = :incident");
            p.addValue("incident", incidentId);
        }
        p.addValue("limit", pageSize).addValue("offset", (long) pageIndex * pageSize);
        List<Notification> items = jdbc.query(NOTIFICATION_SQL + where
                + " ORDER BY n.created_at DESC LIMIT :limit OFFSET :offset", p, this::notification);
        Long total = jdbc.queryForObject("SELECT count(*) FROM notification n" + where, p, Long.class);
        return new Page<>(items, pageIndex, pageSize, total == null ? 0 : total);
    }

    public Notification notification(UUID id) {
        List<Notification> list = jdbc.query(NOTIFICATION_SQL + " WHERE n.id = :id",
                new MapSqlParameterSource("id", id), this::notification);
        if (list.isEmpty()) {
            throw new ProblemException(HttpStatus.NOT_FOUND, "notification-not-found",
                    texts.get("incident.error.notificationNotFound"));
        }
        return list.get(0);
    }

    /** The newest notification awaiting confirmation for an incident code and phone (WhatsApp "OK INC-..."). */
    public UUID awaitingNotification(String code, String phoneMasked) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT n.id FROM notification n JOIN incident i ON i.id = n.incident_id
                 WHERE i.code = :code AND n.kind = 'ALERT' AND (CAST(:phone AS varchar) IS NULL OR n.phone_masked = :phone)
                   AND n.status IN ('SENT', 'DELIVERED', 'ESCALATED', 'SIMULATED', 'PREPARED')
                 ORDER BY n.created_at DESC LIMIT 1
                """, new MapSqlParameterSource("code", code).addValue("phone", phoneMasked), UUID.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void publish(String kind, UUID incidentId, UUID notificationId) {
        try {
            hub.publish(new Incidents.IncidentStreamEvent(kind, summary(incidentId),
                    notificationId == null ? null : notification(notificationId)));
        } catch (RuntimeException e) {
            log.debug("Incident event not published: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- mapping

    private record IncidentRow(UUID id, String code, IncidentType type, Severity severity, IncidentStatus status,
                               Certainty certainty, Source source, String zone, String station, String asset,
                               String description, String owner, Instant createdAt) {
    }

    private IncidentRow row(UUID id) {
        List<IncidentRow> rows = jdbc.query("SELECT * FROM incident WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, i) -> new IncidentRow(rs.getObject("id", UUID.class),
                        rs.getString("code"), IncidentType.valueOf(rs.getString("type")),
                        Severity.valueOf(rs.getString("severity")), IncidentStatus.valueOf(rs.getString("status")),
                        Certainty.valueOf(rs.getString("certainty")), Source.valueOf(rs.getString("source")),
                        rs.getString("zone"), rs.getString("station"), rs.getString("asset"),
                        rs.getString("description"), rs.getString("owner"), instant(rs, "created_at")));
        if (rows.isEmpty()) {
            throw new ProblemException(HttpStatus.NOT_FOUND, "incident-not-found", texts.get("incident.error.notFound"));
        }
        return rows.get(0);
    }

    private long fileCount(UUID id) {
        Long n = jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM incident_file WHERE incident_id = ?",
                Long.class, id);
        return n == null ? 0 : n;
    }

    private Map<String, String> attributes(UUID id) {
        String json = jdbc.getJdbcTemplate().queryForObject("SELECT attributes::text FROM incident WHERE id = ?",
                String.class, id);
        Map<String, String> out = new LinkedHashMap<>();
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).properties()
                    .forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.debug("Incident {} has unreadable attributes", id);
        }
        return out;
    }

    private IncidentSummary summary(ResultSet rs, int row) throws SQLException {
        IncidentType type = IncidentType.valueOf(rs.getString("type"));
        Severity severity = Severity.valueOf(rs.getString("severity"));
        IncidentStatus status = IncidentStatus.valueOf(rs.getString("status"));
        return new IncidentSummary(rs.getObject("id", UUID.class), rs.getString("code"), type,
                texts.get("incident.type." + type.name()), severity, texts.get("incident.severity." + severity.name()),
                status, texts.get("incident.status." + status.name()), Certainty.valueOf(rs.getString("certainty")),
                Source.valueOf(rs.getString("source")), rs.getString("zone"), rs.getString("station"),
                rs.getString("asset"), rs.getString("title"), rs.getString("description"), rs.getString("owner"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "resolved_at"),
                rs.getString("resolution"), rs.getLong("file_count"), rs.getLong("notification_count"),
                rs.getLong("unconfirmed_count"), DataOrigin.valueOf(rs.getString("data_origin")));
    }

    private Notification notification(ResultSet rs, int row) throws SQLException {
        NotificationStatus status = NotificationStatus.valueOf(rs.getString("status"));
        String role = rs.getString("role");
        return new Notification(rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                rs.getString("incident_code"), rs.getString("kind"), rs.getString("recipient_name"), role,
                routing.roleLabel(role), rs.getString("phone_masked"), rs.getString("channel"),
                NotifyMode.valueOf(rs.getString("mode")), status, texts.get("notification.status." + status.name()),
                rs.getInt("level"), rs.getString("body"), instant(rs, "created_at"), instant(rs, "sent_at"),
                instant(rs, "delivered_at"), instant(rs, "confirmed_at"), rs.getString("confirmed_by"),
                instant(rs, "escalated_at"), instant(rs, "escalation_due_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static boolean awaitingConfirmation(NotificationStatus status) {
        return AWAITING_CONFIRMATION.contains(status);
    }
}
