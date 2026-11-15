package ro.filegrid.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Short-lived, HMAC-SHA256 signed links to {@code GET /api/files/{id}/content}
 * (docs/AUTH-DESIGN.md §4): an {@code <img>}, a {@code <video>} or a new browser tab cannot
 * send the bearer token. The signature covers {@code id|disposition|exp|actor|ack}, so a
 * link opens only that file, with that disposition, until it expires, on behalf of the
 * person who asked for it (the audit records that person).
 */
@Component
public class ContentLinkSigner {

    public static final Duration ATTACHMENT_TTL = Duration.ofMinutes(5);
    public static final Duration INLINE_TTL = Duration.ofMinutes(60);
    private static final Logger log = LoggerFactory.getLogger(ContentLinkSigner.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public ContentLinkSigner(SecurityProperties properties) {
        if (properties.linkKey() == null || properties.linkKey().isBlank()) {
            // Workers and tests: links are then valid only inside this process.
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            log.warn("FILEGRID_LINK_KEY is not set: signed content links use a random per-process key");
        } else {
            key = Base64.getDecoder().decode(properties.linkKey().trim());
            if (key.length < 32) {
                throw new IllegalStateException("FILEGRID_LINK_KEY must be at least 256 bit (base64)");
            }
        }
    }

    /** A verified link: who asked for it and whether a suspect file was explicitly acknowledged. */
    public record Grant(UUID fileId, String disposition, String actor, boolean acknowledgeSuspect) {
    }

    public record SignedLink(String url, Instant expiresAt) {
    }

    public SignedLink sign(UUID fileId, String disposition, String actor, boolean acknowledgeSuspect, Instant now) {
        String dispo = "inline".equalsIgnoreCase(disposition) ? "inline" : "attachment";
        Instant expires = now.plus("inline".equals(dispo) ? INLINE_TTL : ATTACHMENT_TTL);
        long exp = expires.getEpochSecond();
        String ack = acknowledgeSuspect ? "1" : "0";
        String sig = mac(fileId, dispo, exp, actor, ack);
        String url = "/api/files/" + fileId + "/content?disposition=" + dispo + "&exp=" + exp
                + "&act=" + java.net.URLEncoder.encode(actor, StandardCharsets.UTF_8)
                + (acknowledgeSuspect ? "&acknowledgeSuspect=true" : "")
                + "&ack=" + ack + "&sig=" + sig;
        return new SignedLink(url, expires);
    }

    /** @return the grant, or {@code null} when the signature is wrong, the link expired or targets another file */
    public Grant verify(UUID fileId, String disposition, String exp, String actor, String ack, String sig,
                        Instant now) {
        if (fileId == null || exp == null || actor == null || sig == null) {
            return null;
        }
        String dispo = "inline".equalsIgnoreCase(disposition) ? "inline" : "attachment";
        String ackValue = "1".equals(ack) ? "1" : "0";
        long expiry;
        try {
            expiry = Long.parseLong(exp);
        } catch (NumberFormatException e) {
            return null;
        }
        if (now.getEpochSecond() > expiry) {
            return null;
        }
        byte[] expected = mac(fileId, dispo, expiry, actor, ackValue).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        return new Grant(fileId, dispo, actor, "1".equals(ackValue));
    }

    private String mac(UUID fileId, String disposition, long exp, String actor, String ack) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] out = mac.doFinal((fileId + "|" + disposition + "|" + exp + "|" + actor + "|" + ack)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
