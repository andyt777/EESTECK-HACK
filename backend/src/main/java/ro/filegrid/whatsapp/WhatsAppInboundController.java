package ro.filegrid.whatsapp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Twilio webhook for incoming WhatsApp messages ({@code POST /api/whatsapp/inbound}). It is the only API
 * endpoint without a bearer token, so nothing happens before the Twilio signature has been verified against
 * the configured public URL. The answer is always an empty TwiML document: PPCastor replies asynchronously.
 */
@RestController
@Profile("api")
public class WhatsAppInboundController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppInboundController.class);
    private static final String EMPTY_TWIML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>";

    private final WhatsAppService service;
    private final WhatsAppProperties props;
    private final Environment env;
    private final AtomicLong lastBadSignatureLog = new AtomicLong();

    public WhatsAppInboundController(WhatsAppService service, WhatsAppProperties props, Environment env) {
        this.service = service;
        this.props = props;
        this.env = env;
    }

    @PostMapping(path = "/whatsapp/inbound", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> inbound(HttpServletRequest request) {
        if (!service.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (request.getQueryString() != null) {
            // The signed URL is the configured one, without a query: anything else is not from our Twilio setup.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v.length > 0 ? v[0] : ""));
        String signature = request.getHeader("X-Twilio-Signature");
        if (!TwilioSignature.valid(env.getProperty("TWILIO_AUTH_TOKEN"), props.webhookUrl(), params, signature)) {
            long now = System.currentTimeMillis();
            if (now - lastBadSignatureLog.get() > 60_000) {
                lastBadSignatureLog.set(now);
                log.warn("WhatsApp webhook rejected: invalid Twilio signature. If the tunnel URL changed, set "
                        + "WHATSAPP_WEBHOOK_URL to the URL configured in Twilio and restart the api.");
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String sid = params.get("MessageSid");
        String from = params.get("From");
        if (sid == null || sid.isBlank() || sid.length() > 64 || from == null || from.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        boolean hasMedia = !"0".equals(params.getOrDefault("NumMedia", "0")) && params.get("MediaUrl0") != null;
        service.accept(new WhatsAppService.Inbound(sid, from, params.get("Body"),
                hasMedia ? params.get("MediaUrl0") : null, hasMedia ? params.get("MediaContentType0") : null));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(EMPTY_TWIML);
    }
}
