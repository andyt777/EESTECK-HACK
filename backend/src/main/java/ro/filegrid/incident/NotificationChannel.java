package ro.filegrid.incident;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * WhatsApp delivery. The demo mode is SIMULATED: the message is only shown in
 * the UI ("telefon simulat"). REAL sends through the Twilio WhatsApp API, and is
 * possible only when TWILIO_* is present in the environment and a number is
 * configured for the recipient.
 */
public interface NotificationChannel {

    /** @return the provider's message id */
    String send(String toPhone, String body);

    boolean configured();

    @Component
    class TwilioWhatsAppChannel implements NotificationChannel {

        private static final Logger log = LoggerFactory.getLogger(TwilioWhatsAppChannel.class);
        private static final Pattern SID = Pattern.compile("\"sid\"\\s*:\\s*\"([A-Za-z0-9]+)\"");

        private final Environment env;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        public TwilioWhatsAppChannel(Environment env) {
            this.env = env;
        }

        @Override
        public boolean configured() {
            return notBlank("TWILIO_ACCOUNT_SID") && notBlank("TWILIO_AUTH_TOKEN") && notBlank("TWILIO_WHATSAPP_FROM");
        }

        private boolean notBlank(String key) {
            String v = env.getProperty(key);
            return v != null && !v.isBlank();
        }

        @Override
        public String send(String toPhone, String body) {
            if (!configured()) {
                throw new IllegalStateException("Twilio is not configured");
            }
            String sid = env.getProperty("TWILIO_ACCOUNT_SID");
            String auth = Base64.getEncoder().encodeToString(
                    (sid + ":" + env.getProperty("TWILIO_AUTH_TOKEN")).getBytes(StandardCharsets.UTF_8));
            String form = "From=" + enc("whatsapp:" + env.getProperty("TWILIO_WHATSAPP_FROM"))
                    + "&To=" + enc("whatsapp:" + toPhone) + "&Body=" + enc(body);
            String callback = env.getProperty("TWILIO_STATUS_CALLBACK_URL");
            if (callback != null && !callback.isBlank()) {
                form += "&StatusCallback=" + enc(callback);
            }
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + enc(sid) + "/Messages.json"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Basic " + auth)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Twilio answered HTTP " + response.statusCode());
                }
                Matcher m = SID.matcher(response.body());
                return m.find() ? m.group(1) : null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while sending", e);
            } catch (java.io.IOException e) {
                log.warn("WhatsApp message could not be sent: {}", e.getMessage());
                throw new IllegalStateException("Twilio is unreachable", e);
            }
        }

        private static String enc(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
