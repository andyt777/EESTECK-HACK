package ro.filegrid.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TwilioSignatureTest {

    // The worked example from https://www.twilio.com/docs/usage/security ("Explore the algorithm yourself").
    private static final String TOKEN = "12345";
    private static final String URL = "https://example.com/myapp.php?foo=1&bar=2";
    private static final Map<String, String> PARAMS = Map.of("CallSid", "CA1234567890ABCDE", "Caller",
            "+14158675310", "Digits", "1234", "From", "+14158675310", "To", "+18005551212");
    private static final String EXPECTED = "L/OH5YylLD5NRKLltdqwSvS0BnU=";

    @Test
    void matchesTheTwilioDocumentationVector() {
        assertThat(TwilioSignature.compute(TOKEN, URL, PARAMS)).isEqualTo(EXPECTED);
        assertThat(TwilioSignature.valid(TOKEN, URL, PARAMS, EXPECTED)).isTrue();
    }

    @Test
    void rejectsTamperedParamsUrlTokenOrMissingHeader() {
        assertThat(TwilioSignature.valid(TOKEN, URL, Map.of("Digits", "9999"), EXPECTED)).isFalse();
        assertThat(TwilioSignature.valid(TOKEN, "https://evil.example/myapp.php?foo=1&bar=2", PARAMS, EXPECTED))
                .isFalse();
        assertThat(TwilioSignature.valid("other", URL, PARAMS, EXPECTED)).isFalse();
        assertThat(TwilioSignature.valid(TOKEN, URL, PARAMS, null)).isFalse();
        assertThat(TwilioSignature.valid(TOKEN, URL, PARAMS, "")).isFalse();
        assertThat(TwilioSignature.valid("", URL, PARAMS, EXPECTED)).isFalse();
    }
}
