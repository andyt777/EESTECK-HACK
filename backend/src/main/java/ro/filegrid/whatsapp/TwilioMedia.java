package ro.filegrid.whatsapp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * Downloads the media of an inbound WhatsApp message from Twilio as a stream. Only Twilio URLs are fetched
 * (no request to an arbitrary host), the account credentials go only to Twilio, and the stream fails as soon
 * as it exceeds the configured cap.
 */
public class TwilioMedia {

    /** The media is larger than allowed. */
    public static class TooLargeException extends IOException {
        public TooLargeException(long max) {
            super("media larger than " + max + " bytes");
        }
    }

    private final String accountSid;
    private final String authToken;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER) // the redirect target must not receive our credentials
            .build();

    public TwilioMedia(String accountSid, String authToken) {
        this.accountSid = accountSid;
        this.authToken = authToken;
    }

    static boolean isTwilioUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && (host.equals("api.twilio.com") || host.endsWith(".twilio.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Opens the media; the caller closes the stream. */
    public InputStream open(String mediaUrl, long maxBytes) throws IOException {
        if (!isTwilioUrl(mediaUrl)) {
            throw new IOException("refusing to fetch media outside Twilio");
        }
        String basic = Base64.getEncoder().encodeToString((accountSid + ":" + authToken)
                .getBytes(StandardCharsets.UTF_8));
        HttpResponse<InputStream> response = get(HttpRequest.newBuilder(URI.create(mediaUrl))
                .header("Authorization", "Basic " + basic));
        if (response.statusCode() / 100 == 3) {
            String location = response.headers().firstValue("Location").orElseThrow(
                    () -> new IOException("redirect without Location"));
            response.body().close();
            URI target = URI.create(mediaUrl).resolve(location);
            if (!"https".equals(target.getScheme())) {
                throw new IOException("refusing a non-https media redirect");
            }
            response = get(HttpRequest.newBuilder(target)); // signed storage URL: no credentials
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("media download answered HTTP " + response.statusCode());
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxBytes) {
            response.body().close();
            throw new TooLargeException(maxBytes);
        }
        return new Bounded(response.body(), maxBytes);
    }

    private HttpResponse<InputStream> get(HttpRequest.Builder builder) throws IOException {
        try {
            return http.send(builder.timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Fails once more than {@code max} bytes have been read. */
    static final class Bounded extends FilterInputStream {
        private final long max;
        private long count;

        Bounded(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++count > max) {
                throw new TooLargeException(max);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0 && (count += n) > max) {
                throw new TooLargeException(max);
            }
            return n;
        }
    }
}
