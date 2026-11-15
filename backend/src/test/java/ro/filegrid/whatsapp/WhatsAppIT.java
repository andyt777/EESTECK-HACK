package ro.filegrid.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.incident.IncidentService;
import ro.filegrid.incident.Incidents.Certainty;
import ro.filegrid.incident.Incidents.IncidentDetail;
import ro.filegrid.incident.Incidents.IncidentType;
import ro.filegrid.incident.Incidents.NewIncident;
import ro.filegrid.incident.Incidents.Severity;
import ro.filegrid.incident.Incidents.Source;
import ro.filegrid.incident.NotificationChannel;
import ro.filegrid.testsupport.TestImages;

/**
 * PPCastor on WhatsApp end to end inside the api: Twilio signature, known senders only, MessageSid
 * deduplication, "OK" confirming a real alert, a field report that becomes an incident only after "DA",
 * and questions answered by the assistant. Twilio and the intelligence service are mocked.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
class WhatsAppIT {

    private static final String WEBHOOK = "https://ppcastor.example/api/whatsapp/inbound";
    private static final String TOKEN = "twilio-test-token";
    private static final String FIELD = "+40722000222";
    private static final String STRANGER = "+40799999999";

    @Container
    static final PostgreSQLContainer<?> META_DB = new PostgreSQLContainer<>(TestImages.PGVECTOR)
            .withDatabaseName("filegrid_meta");

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(TestImages.RABBITMQ);

    @Container
    static final MinIOContainer S3 = new MinIOContainer(TestImages.S3);

    @TempDir
    static Path stagingDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", META_DB::getJdbcUrl);
        registry.add("spring.datasource.username", META_DB::getUsername);
        registry.add("spring.datasource.password", META_DB::getPassword);
        registry.add("filegrid.store-db.url", STORE_DB::getJdbcUrl);
        registry.add("filegrid.store-db.username", STORE_DB::getUsername);
        registry.add("filegrid.store-db.password", STORE_DB::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("filegrid.staging.directory", () -> stagingDir.toAbsolutePath().toString());
        registry.add("filegrid.storage.s3.endpoint", S3::getS3URL);
        registry.add("filegrid.storage.s3.access-key", S3::getUserName);
        registry.add("filegrid.storage.s3.secret-key", S3::getPassword);
        registry.add("filegrid.notify.mode", () -> "REAL");
        registry.add("filegrid.whatsapp.webhook-url", () -> WEBHOOK);
        registry.add("TWILIO_AUTH_TOKEN", () -> TOKEN);
        registry.add("NOTIFY_CONTACT_FIELD_TEAM", () -> "+40 722 000 222");
        registry.add("NOTIFY_CONTACT_DISPATCH", () -> "+40722000111");
        // Same mask as the field team ("+40 *****222"): an OK must still confirm only the sender's own alert.
        registry.add("NOTIFY_CONTACT_TECH_MANAGER", () -> "+40 799 000 222");
    }

    @MockitoBean
    NotificationChannel channel;

    @MockitoBean
    IntelligenceClient intelligence;

    @MockitoBean
    TwilioMedia media;

    @LocalServerPort
    int port;

    @Autowired
    IncidentService incidents;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void twilio() {
        reset(channel, intelligence, media);
        when(channel.configured()).thenReturn(true);
        when(channel.send(anyString(), anyString())).thenReturn("SM" + UUID.randomUUID().toString().replace("-", ""));
    }

    private HttpResponse<String> post(Map<String, String> form, String signature) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/whatsapp/inbound"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            b.header("X-Twilio-Signature", signature);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> message(String from, String body) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("MessageSid", "SM" + UUID.randomUUID().toString().replace("-", ""));
        form.put("From", "whatsapp:" + from);
        form.put("To", "whatsapp:+14155238886");
        form.put("Body", body);
        form.put("NumMedia", "0");
        return post(form, TwilioSignature.compute(TOKEN, WEBHOOK, form));
    }

    private HttpResponse<String> mediaMessage(String from, String caption, String type) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("MessageSid", "SM" + UUID.randomUUID().toString().replace("-", ""));
        form.put("From", "whatsapp:" + from);
        form.put("Body", caption);
        form.put("NumMedia", "1");
        form.put("MediaUrl0", "https://api.twilio.com/2010-04-01/Accounts/AC1/Messages/MM1/Media/ME1");
        form.put("MediaContentType0", type);
        return post(form, TwilioSignature.compute(TOKEN, WEBHOOK, form));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void verifyReply(String containing) {
        verify(channel, timeout(10_000).atLeastOnce())
                .send(eq(FIELD), argThat(b -> b.startsWith("🦫 *PPCastor*:") && b.contains(containing)));
    }

    @Test
    @DisplayName("a request without a valid Twilio signature is refused before anything happens")
    void signatureRequired() throws Exception {
        Map<String, String> form = Map.of("MessageSid", "SM-unsigned", "From", "whatsapp:" + FIELD, "Body", "OK");
        assertThat(post(form, null).statusCode()).isEqualTo(403);
        assertThat(post(form, TwilioSignature.compute("wrong", WEBHOOK, form)).statusCode()).isEqualTo(403);
        assertThat(post(form, TwilioSignature.compute(TOKEN, "https://other.example/x", form)).statusCode())
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM whatsapp_inbound WHERE message_sid = 'SM-unsigned'",
                Map.of(), Long.class)).isZero();
    }

    @Test
    @DisplayName("an unknown number gets one fixed answer and nothing else")
    void unknownSender() throws Exception {
        assertThat(message(STRANGER, "raport incendiu").statusCode()).isEqualTo(200);
        verify(channel, timeout(10_000)).send(eq(STRANGER), argThat(b -> b.contains("nu este înregistrat")));
        verify(intelligence, never()).proposeReport(anyString());
    }

    @Test
    @DisplayName("Twilio retrying the same MessageSid is handled once")
    void duplicateIgnored() throws Exception {
        when(intelligence.ask(anyString())).thenReturn("Nu sunt incidente deschise.");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("MessageSid", "SM-dup-1");
        form.put("From", "whatsapp:" + FIELD);
        form.put("Body", "Ce incidente sunt deschise?");
        String sig = TwilioSignature.compute(TOKEN, WEBHOOK, form);
        assertThat(post(form, sig).statusCode()).isEqualTo(200);
        assertThat(post(form, sig).statusCode()).isEqualTo(200);
        verifyReply("Nu sunt incidente deschise.");
        Thread.sleep(500);
        verify(intelligence, times(1)).ask("Ce incidente sunt deschise?");
    }

    @Test
    @DisplayName("OK confirms the newest open alert sent to that phone")
    void okConfirmsTheAlert() throws Exception {
        IncidentDetail opened = incidents.open(new NewIncident(IncidentType.GRID_FAULT_MAJOR, Severity.HIGH,
                Certainty.CONFIRMED, Source.USER, "Muntenia", "Stația Nord", "Trafo T2", null,
                "Declanșare protecție", DataOrigin.SIMULATED, null, List.of(), "test"));
        String code = opened.incident().code();

        assertThat(message(FIELD, "OK " + code).statusCode()).isEqualTo(200);

        verifyReply(code);
        Awaitility.await().untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification n JOIN incident i ON i.id = n.incident_id
                 WHERE i.code = :code AND n.status = 'CONFIRMED' AND n.confirmed_by = 'whatsapp:FIELD_TEAM'
                """, new MapSqlParameterSource("code", code), Long.class)).isEqualTo(1));
        assertThat(jdbc.queryForList("""
                SELECT n.role FROM notification n JOIN incident i ON i.id = n.incident_id
                 WHERE i.code = :code AND n.status = 'CONFIRMED'
                """, new MapSqlParameterSource("code", code), String.class)).containsExactly("FIELD_TEAM");
    }

    @Test
    @DisplayName("a field report becomes an incident only after DA, with dataOrigin REAL")
    void reportNeedsApproval() throws Exception {
        when(intelligence.proposeReport(anyString())).thenReturn(json.readTree("""
                {"type":"FIRE_REPORTED","severity":"CRITICAL","certainty":"REPORTED","source":"USER",
                 "zone":"Tulcea","station":"ST-DOB-04","asset":"PT-DOB-04-03",
                 "title":"Incendiu raportat din teren","description":"fum la PT-DOB-04-03"}"""));

        message(FIELD, "raport fum la PT-DOB-04-03");
        verifyReply("Răspunde *DA*");
        long before = jdbc.queryForObject("SELECT count(*) FROM incident", Map.of(), Long.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM whatsapp_pending_report", Map.of(), Long.class))
                .isEqualTo(1);

        message(FIELD, "DA");
        verifyReply("Am deschis incidentul INC-");
        Awaitility.await().untilAsserted(() -> assertThat(jdbc.queryForList("""
                SELECT data_origin FROM incident WHERE type = 'FIRE_REPORTED' AND created_by = 'whatsapp:FIELD_TEAM'
                """, Map.of(), String.class)).containsExactly("REAL"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident", Map.of(), Long.class)).isEqualTo(before + 1);

        reset(channel);
        when(channel.configured()).thenReturn(true);
        message(FIELD, "DA");
        verifyReply("Nu am niciun raport");
    }

    @Test
    @DisplayName("a free question is answered by the assistant")
    void questionAnswered() throws Exception {
        when(intelligence.ask("Câte incidente sunt în Tulcea?")).thenReturn("Un singur incident: INC-0001.");
        message(FIELD, "Câte incidente sunt în Tulcea?");
        verifyReply("Un singur incident: INC-0001.");
    }

    @Test
    @DisplayName("links in an assistant answer never leave the platform (no exfiltration via link previews)")
    void linksStrippedFromAnswers() throws Exception {
        when(intelligence.ask("Unde e documentația?"))
                .thenReturn("Vezi https://evil.example/x?d=INC-0001 și http://a.b/c pentru detalii.");
        message(FIELD, "Unde e documentația?");
        verifyReply("pentru detalii");
        verify(channel, never()).send(eq(FIELD), argThat(b -> b.contains("evil.example") || b.contains("http")));
    }

    @Test
    @DisplayName("a voice note without transcription asks for text and creates nothing")
    void voiceWithoutTranscription() throws Exception {
        when(media.open(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new java.io.ByteArrayInputStream("OggS".getBytes(StandardCharsets.UTF_8)));
        when(intelligence.transcribe(org.mockito.ArgumentMatchers.any(), anyString()))
                .thenThrow(new IntelligenceClient.IntelligenceException(503, "not configured"));
        mediaMessage(FIELD, "", "audio/ogg");
        verifyReply("Trimite raportul ca text");
        verify(intelligence, never()).proposeReport(anyString());
    }

    @Test
    @DisplayName("a photo is stored as a REAL FileGrid file and linked to the incident after DA")
    void photoBecomesAFileOfTheIncident() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};
        when(media.open(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new java.io.ByteArrayInputStream(jpeg));
        when(intelligence.proposeReport(anyString())).thenReturn(json.readTree("""
                {"type":"GRID_FAULT_MAJOR","severity":"MEDIUM","zone":"Banat","title":"Avarie raportată din teren",
                 "description":"stâlp căzut"}"""));
        mediaMessage(FIELD, "stâlp căzut", "image/jpeg");
        verifyReply("Poza a fost primită");
        UUID fileId = jdbc.queryForObject("SELECT file_id FROM whatsapp_pending_report", Map.of(), UUID.class);
        assertThat(jdbc.queryForObject("SELECT data_origin FROM file_record WHERE id = :id",
                Map.of("id", fileId), String.class)).isEqualTo("REAL");

        message(FIELD, "DA");
        verifyReply("Am deschis incidentul");
        Awaitility.await().untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM incident_file WHERE file_id = :id", Map.of("id", fileId), Long.class))
                .isEqualTo(1));
    }
}
