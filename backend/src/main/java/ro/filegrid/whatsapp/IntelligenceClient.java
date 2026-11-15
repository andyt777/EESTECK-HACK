package ro.filegrid.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Calls the intelligence service on behalf of the WhatsApp channel, with a Keycloak client-credentials token
 * of {@code filegrid-whatsapp} (role {@code svc-whatsapp}): the existing permission checks apply unchanged,
 * and the assistant never sees more than that role allows (no personal data).
 */
public class IntelligenceClient {

    /** The intelligence service answered with an error status. */
    public static class IntelligenceException extends IOException {
        private final int status;

        public IntelligenceException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final WhatsAppProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public IntelligenceClient(WhatsAppProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    /** PPCastor assistant answer to a free question. */
    public String ask(String question) throws IOException {
        JsonNode r = post("/assistant/chat", Map.of("message", question, "history", java.util.List.of()));
        return r.path("answer").asText("");
    }

    /** Deterministic field-report proposal (type, severity, zone, station, asset, title, description). */
    public JsonNode proposeReport(String text) throws IOException {
        return post("/voice/field-report", Map.of("transcript", text, "dataOrigin", "REAL", "create", false))
                .path("proposal");
    }

    /** Voice note -> text (Gemini). Throws {@link IntelligenceException} 503 when transcription is not set up. */
    public String transcribe(Path audio, String contentType) throws IOException {
        HttpRequest request = authorized("/voice/transcribe")
                .header("Content-Type", contentType)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofFile(audio)).build();
        return send(request).path("transcript").asText("");
    }

    private JsonNode post(String path, Object body) throws IOException {
        HttpRequest request = authorized(path)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return send(request);
    }

    private HttpRequest.Builder authorized(String path) throws IOException {
        return HttpRequest.newBuilder(URI.create(props.intelligenceUrl().replaceAll("/+$", "") + path))
                .header("Authorization", "Bearer " + token());
    }

    private JsonNode send(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                synchronized (this) {
                    tokenExpiresAt = Instant.EPOCH; // refreshed on the next call
                }
            }
            if (response.statusCode() / 100 != 2) {
                throw new IntelligenceException(response.statusCode(), "intelligence answered HTTP "
                        + response.statusCode());
            }
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private synchronized String token() throws IOException {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        if (props.clientSecret() == null || props.clientSecret().isBlank()) {
            throw new IOException("KC_WHATSAPP_SECRET is not set");
        }
        String form = "grant_type=client_credentials&client_id=" + enc(props.clientId())
                + "&client_secret=" + enc(props.clientSecret());
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(props.tokenUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("token endpoint answered HTTP " + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            token = body.path("access_token").asText(null);
            long expiresIn = body.path("expires_in").asLong(60);
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(10, expiresIn - 30));
            if (token == null) {
                throw new IOException("token endpoint returned no access_token");
            }
            return token;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
