package ro.filegrid.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileSource;
import ro.filegrid.incident.IncidentService;
import ro.filegrid.incident.Incidents.Certainty;
import ro.filegrid.incident.Incidents.IncidentDetail;
import ro.filegrid.incident.Incidents.IncidentType;
import ro.filegrid.incident.Incidents.NewIncident;
import ro.filegrid.incident.Incidents.Notification;
import ro.filegrid.incident.Incidents.NotifyMode;
import ro.filegrid.incident.Incidents.Severity;
import ro.filegrid.incident.Incidents.Source;
import ro.filegrid.incident.NotificationChannel;
import ro.filegrid.incident.NotificationRouting;
import ro.filegrid.text.Texts;
import ro.filegrid.upload.FileIntakeService;
import ro.filegrid.whatsapp.WhatsAppIntents.Intent;
import ro.filegrid.whatsapp.WhatsAppIntents.Kind;

/**
 * PPCastor on WhatsApp: what happens with one inbound message. The controller has already checked the Twilio
 * signature; here the sender must be a configured contact, the message is recorded once (by MessageSid,
 * without its text) and handled on a small worker pool, and the answer goes back through Twilio.
 */
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);
    static final int MAX_REPLY_CHARS = 1500; // WhatsApp/Twilio body limit is 1600

    /** One inbound message (form fields of the Twilio webhook). */
    public record Inbound(String messageSid, String from, String body, String mediaUrl, String mediaType) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final IncidentService incidents;
    private final NotificationRouting routing;
    private final NotificationChannel channel;
    private final FileIntakeService intake;
    private final IntelligenceClient intelligence;
    private final TwilioMedia media;
    private final WhatsAppContacts contacts;
    private final WhatsAppProperties props;
    private final Texts texts;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers;

    public WhatsAppService(NamedParameterJdbcTemplate jdbc, IncidentService incidents, NotificationRouting routing,
                           NotificationChannel channel, FileIntakeService intake, IntelligenceClient intelligence,
                           TwilioMedia media, WhatsAppContacts contacts, WhatsAppProperties props, Texts texts,
                           ObjectMapper json) {
        this.jdbc = jdbc;
        this.incidents = incidents;
        this.routing = routing;
        this.channel = channel;
        this.intake = intake;
        this.intelligence = intelligence;
        this.media = media;
        this.contacts = contacts;
        this.props = props;
        this.texts = texts;
        this.json = json;
        int n = Math.max(1, props.workers());
        this.workers = new ThreadPoolExecutor(n, n, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "whatsapp-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    @PreDestroy
    void stop() {
        workers.shutdown();
    }

    /** The inbound webhook is served only in REAL mode with Twilio and a public URL configured. */
    public boolean enabled() {
        return incidents.mode() == NotifyMode.REAL && channel.configured()
                && props.webhookUrl() != null && !props.webhookUrl().isBlank();
    }

    /**
     * Records the message (once per MessageSid) and queues it. Returns false for a duplicate (Twilio retry).
     * Runs inside the webhook request, so it is fast; the answer is sent later.
     */
    public boolean accept(Inbound in) {
        Optional<WhatsAppContacts.Match> sender = contacts.find(in.from());
        Intent intent = WhatsAppIntents.parse(in.body(), in.mediaUrl() == null ? null : in.mediaType());
        String masked = sender.map(m -> mask(m.configuredPhone())).orElse(mask(WhatsAppContacts.normalise(in.from())));
        int inserted = jdbc.update("""
                INSERT INTO whatsapp_inbound (message_sid, phone_masked, role, kind, status)
                VALUES (:sid, :masked, :role, :kind, :status) ON CONFLICT (message_sid) DO NOTHING
                """, new MapSqlParameterSource("sid", in.messageSid()).addValue("masked", masked)
                .addValue("role", sender.map(WhatsAppContacts.Match::role).orElse(null))
                .addValue("kind", intent.kind().name()).addValue("status", sender.isPresent() ? "RECEIVED" : "IGNORED"));
        if (inserted == 0) {
            return false;
        }
        try {
            workers.execute(() -> handle(in, sender, intent));
        } catch (RejectedExecutionException e) {
            log.warn("WhatsApp queue full, message {} dropped", in.messageSid());
            status(in.messageSid(), "FAILED");
        }
        return true;
    }

    void handle(Inbound in, Optional<WhatsAppContacts.Match> sender, Intent intent) {
        String to = WhatsAppContacts.normalise(in.from());
        if (sender.isEmpty()) {
            reply(to, texts.get("whatsapp.unknownSender"));
            return;
        }
        WhatsAppContacts.Match who = sender.get();
        String actor = "whatsapp:" + who.role();
        String status = "DONE";
        try {
            String answer = switch (intent.kind()) {
                case CONFIRM -> confirm(who, intent.incidentCode(), actor);
                case APPROVE -> approve(to, actor);
                case REJECT -> reject(to);
                case REPORT_TEXT -> intent.text().isBlank() ? texts.get("whatsapp.reportEmpty")
                        : propose(to, who, intent.text(), null, null);
                case REPORT_VOICE -> voice(to, who, in);
                case REPORT_PHOTO -> photo(to, who, in, intent.text(), actor);
                case QUESTION -> ask(intent.text());
                case UNSUPPORTED -> texts.get("whatsapp.unsupported");
                case EMPTY -> texts.get("whatsapp.help");
            };
            reply(to, answer);
        } catch (TwilioMedia.TooLargeException | FileIntakeService.TooLargeException e) {
            status = "FAILED";
            reply(to, texts.get("whatsapp.mediaTooLarge"));
        } catch (Exception e) {
            status = "FAILED";
            log.warn("WhatsApp message {} ({}) failed: {}", in.messageSid(), intent.kind(), e.toString());
            reply(to, texts.get("whatsapp.unavailable"));
        }
        status(in.messageSid(), status);
    }

    // ----------------------------------------------------------------- intents

    private String confirm(WhatsAppContacts.Match who, String code, String actor) {
        UUID id = awaitingFor(who, code);
        if (id == null) {
            return texts.get("whatsapp.confirmNone");
        }
        Notification n = incidents.confirm(id, actor);
        return texts.get("whatsapp.confirmed", n.incidentCode());
    }

    /**
     * The newest open alert sent to exactly this sender. The stored mask only narrows the search: two numbers
     * can share a mask, so each candidate's recipient number is resolved again and compared in full.
     */
    private UUID awaitingFor(WhatsAppContacts.Match who, String code) {
        String sender = WhatsAppContacts.normalise(who.configuredPhone());
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT n.id, n.role, i.zone FROM notification n JOIN incident i ON i.id = n.incident_id
                 WHERE n.kind = 'ALERT' AND n.phone_masked = :phone
                   AND (CAST(:code AS varchar) IS NULL OR i.code = :code)
                   AND n.status IN ('SENT', 'DELIVERED', 'ESCALATED', 'SIMULATED', 'PREPARED')
                 ORDER BY n.created_at DESC LIMIT 20
                """, new MapSqlParameterSource("phone", mask(who.configuredPhone())).addValue("code", code));
        for (Map<String, Object> c : candidates) {
            NotificationRouting.Contact recipient = routing.contact((String) c.get("role"), (String) c.get("zone"));
            if (sender.equals(WhatsAppContacts.normalise(recipient.phone()))) {
                return (UUID) c.get("id");
            }
        }
        return null;
    }

    private String propose(String phone, WhatsAppContacts.Match who, String text, UUID fileId, String prefix)
            throws IOException {
        JsonNode p = intelligence.proposeReport(text);
        jdbc.update("""
                INSERT INTO whatsapp_pending_report (phone_hash, role, proposal, file_id, expires_at)
                VALUES (:h, :role, CAST(:p AS jsonb), :file, now() + interval '15 minutes')
                ON CONFLICT (phone_hash) DO UPDATE SET role = EXCLUDED.role, proposal = EXCLUDED.proposal,
                       file_id = EXCLUDED.file_id, expires_at = EXCLUDED.expires_at
                """, new MapSqlParameterSource("h", hash(phone)).addValue("role", who.role())
                .addValue("p", json.writeValueAsString(p)).addValue("file", fileId));
        String place = String.join(" / ", java.util.stream.Stream.of("zone", "station", "asset")
                .map(k -> p.path(k).asText("")).filter(s -> !s.isBlank()).toList());
        String summary = texts.get("whatsapp.reportProposal",
                p.path("title").asText(p.path("type").asText("")),
                p.path("severity").asText(""),
                place.isBlank() ? texts.get("whatsapp.place.unknown") : place,
                p.path("description").asText(""));
        return prefix == null ? summary : prefix + "\n\n" + summary;
    }

    private String approve(String phone, String actor) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                DELETE FROM whatsapp_pending_report WHERE phone_hash = :h AND expires_at > now()
                RETURNING proposal::text AS proposal, file_id
                """, new MapSqlParameterSource("h", hash(phone)));
        if (rows.isEmpty()) {
            return texts.get("whatsapp.approveNone");
        }
        JsonNode p;
        try {
            p = json.readTree((String) rows.get(0).get("proposal"));
        } catch (IOException e) {
            throw new IllegalStateException("stored proposal is not JSON", e);
        }
        UUID fileId = (UUID) rows.get(0).get("file_id");
        IncidentDetail opened = incidents.open(new NewIncident(
                enumOr(IncidentType.class, p.path("type").asText(), IncidentType.GRID_ANOMALY),
                enumOr(Severity.class, p.path("severity").asText(), Severity.MEDIUM),
                Certainty.REPORTED, Source.USER,
                text(p, "zone"), text(p, "station"), text(p, "asset"),
                p.path("title").asText("Raport din teren (WhatsApp)"),
                p.path("description").asText(""),
                DataOrigin.REAL, null, fileId == null ? List.of() : List.of(fileId), actor));
        return texts.get("whatsapp.incidentOpened", opened.incident().code());
    }

    private String reject(String phone) {
        jdbc.update("DELETE FROM whatsapp_pending_report WHERE phone_hash = :h",
                new MapSqlParameterSource("h", hash(phone)));
        return texts.get("whatsapp.rejected");
    }

    private String voice(String phone, WhatsAppContacts.Match who, Inbound in) throws IOException {
        Path tmp = Files.createTempFile("whatsapp-voice-", ".audio");
        try {
            try (InputStream src = media.open(in.mediaUrl(), props.mediaMaxBytes());
                 OutputStream out = Files.newOutputStream(tmp)) {
                src.transferTo(out); // streamed with the default 8 KB buffer, capped by TwilioMedia
            }
            String transcript;
            try {
                transcript = intelligence.transcribe(tmp, in.mediaType());
            } catch (IntelligenceClient.IntelligenceException e) {
                return texts.get(e.status() == 503 ? "whatsapp.voiceUnavailable" : "whatsapp.voiceFailed");
            }
            if (transcript.isBlank()) {
                return texts.get("whatsapp.voiceFailed");
            }
            return propose(phone, who, transcript, null, texts.get("whatsapp.reportVoice", transcript));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private String photo(String phone, WhatsAppContacts.Match who, Inbound in, String caption, String actor)
            throws IOException {
        String ext = in.mediaType() != null && in.mediaType().toLowerCase(Locale.ROOT).contains("png") ? "png" : "jpg";
        FileRecord file;
        try (InputStream src = media.open(in.mediaUrl(), props.mediaMaxBytes())) {
            file = intake.accept(src, "whatsapp-" + in.messageSid() + "." + ext, in.mediaType(), null,
                    new FileIntakeService.Context(null, null, null, null, "FIELD_PHOTO", null,
                            caption == null || caption.isBlank() ? null : caption),
                    DataOrigin.REAL, "whatsapp:" + in.messageSid(), FileSource.API, actor, "WHATSAPP");
        }
        String text = caption == null || caption.isBlank() ? "Fotografie trimisă din teren" : caption;
        return propose(phone, who, text, file.getId(), texts.get("whatsapp.reportPhoto"));
    }

    private String ask(String question) throws IOException {
        try {
            String answer = intelligence.ask(question);
            return answer == null || answer.isBlank() ? texts.get("whatsapp.unavailable") : withoutLinks(answer);
        } catch (IOException e) {
            log.warn("assistant unavailable for WhatsApp: {}", e.toString());
            return texts.get("whatsapp.unavailable");
        }
    }

    // ----------------------------------------------------------------- helpers

    private void reply(String to, String text) {
        String body = texts.get("whatsapp.signature") + " " + text;
        if (body.length() > MAX_REPLY_CHARS) {
            body = body.substring(0, MAX_REPLY_CHARS - 1) + "…";
        }
        try {
            channel.send(to, body);
        } catch (RuntimeException e) {
            log.warn("WhatsApp reply to {} failed: {}", mask(to), e.getMessage());
        }
    }

    private void status(String sid, String status) {
        jdbc.update("UPDATE whatsapp_inbound SET status = :s WHERE message_sid = :sid",
                new MapSqlParameterSource("s", status).addValue("sid", sid));
    }

    /** Same masking as {@link NotificationRouting.Contact#masked()}, so notifications can be matched. */
    static String mask(String phone) {
        String m = new NotificationRouting.Contact(null, null, null, phone).masked();
        return m == null ? "unknown" : m;
    }

    static String hash(String phone) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(WhatsAppContacts.normalise(phone)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Assistant answers may echo user-controlled data (prompt injection): links are removed so nothing can be
     * exfiltrated through a URL or a WhatsApp link preview. Sources stay visible in the FileGrid UI.
     */
    static String withoutLinks(String text) {
        return LINK.matcher(text).replaceAll("[link eliminat]");
    }

    private static final java.util.regex.Pattern LINK = java.util.regex.Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+");

    private static String text(JsonNode p, String field) {
        String v = p.path(field).asText(null);
        return v == null || v.isBlank() || "null".equals(v) ? null : v;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback;
        }
    }
}
