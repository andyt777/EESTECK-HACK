package ro.filegrid.incident;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TwilioErrorTest {

    @Test
    void extractsCodeAndMessageFromTwilioErrorBody() {
        String body = "{\"code\": 63015, \"message\": \"Channel Sandbox can only send messages to phone numbers "
                + "that have joined the Sandbox\", \"more_info\": \"https://www.twilio.com/docs/errors/63015\", "
                + "\"status\": 400}";
        assertThat(NotificationChannel.TwilioWhatsAppChannel.twilioError(body)).isEqualTo(
                " (Twilio error 63015: Channel Sandbox can only send messages to phone numbers "
                        + "that have joined the Sandbox)");
    }

    @Test
    void toleratesEmptyOrUnexpectedBody() {
        assertThat(NotificationChannel.TwilioWhatsAppChannel.twilioError(null)).isEmpty();
        assertThat(NotificationChannel.TwilioWhatsAppChannel.twilioError("<html>bad gateway</html>"))
                .isEqualTo(" (Twilio error ?)");
    }

    @Test
    void capsLongMessages() {
        String body = "{\"code\": 1, \"message\": \"" + "x".repeat(500) + "\"}";
        assertThat(NotificationChannel.TwilioWhatsAppChannel.twilioError(body)).hasSizeLessThan(240);
    }
}
