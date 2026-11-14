package ro.filegrid.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Short-lived, HMAC-SHA256 signed content URLs for elements that cannot send an
 * {@code Authorization} header ({@code <img>}, {@code <video>}, iframe, new tab).
 * The signature covers {@code id|disposition|exp|actor|suspectAck}, so a link
 * opens exactly one file, in one disposition, for the actor it was issued to,
 * until it expires. Permissions are checked when the link is issued.
 */
@Component
@Profile("api")
public class ContentLinkSigner {

    public static final String INLINE = "inline";
    public static final String ATTACHMENT = "attachment";
    private static final String HMAC = "HmacSHA256";
    private static final int MIN_KEY_LENGTH = 32;

    /** A signed link and the moment it stops working. */
    public record SignedLink(String url, Instant expiresAt) {
    }

    private final SecretKeySpec key;
    private final Duration ttlAttachment;
    private final Duration ttlInline;
    private final String contextPath;
    private final Clock clock;

    @Autowired
    public ContentLinkSigner(FileGridSecurityProperties properties,
                             @Value("${server.servlet.context-path:}") String contextPath) {
        this(properties, contextPath, Clock.systemUTC());
    }

    ContentLinkSigner(FileGridSecurityProperties properties, String contextPath, Clock clock) {
        String linkKey = properties.linkKey();
        if (linkKey == null || linkKey.isBlank() || linkKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("filegrid.security.link-key (FILEGRID_LINK_KEY) must be set to at least "
                    + MIN_KEY_LENGTH + " characters: run scripts/init-env.sh");
        }
        this.key = new SecretKeySpec(linkKey.getBytes(StandardCharsets.UTF_8), HMAC);
        this.ttlAttachment = properties.linkTtlAttachment();
        this.ttlInline = properties.linkTtlInline();
        this.contextPath = contextPath == null ? "" : contextPath;
        this.clock = clock;
    }

    /** @return {@code inline} or {@code attachment} (anything else counts as attachment) */
    public static String normalizeDisposition(String disposition) {
        return INLINE.equalsIgnoreCase(disposition == null ? "" : disposition.trim()) ? INLINE : ATTACHMENT;
    }

    public SignedLink sign(UUID fileId, String disposition, String actor, boolean suspectAck) {
        String normalized = normalizeDisposition(disposition);
        Instant expiresAt = clock.instant().plus(INLINE.equals(normalized) ? ttlInline : ttlAttachment)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        long exp = expiresAt.getEpochSecond();
        String signature = hmac(fileId, normalized, exp, actor, suspectAck);
        StringBuilder url = new StringBuilder(contextPath).append("/files/").append(fileId).append("/content")
                .append("?disposition=").append(normalized)
                .append("&exp=").append(exp)
                .append("&act=").append(URLEncoder.encode(actor, StandardCharsets.UTF_8))
                .append("&sig=").append(signature);
        if (suspectAck) {
            url.append("&acknowledgeSuspect=true");
        }
        return new SignedLink(url.toString(), expiresAt);
    }

    /**
     * @return the actor the link was issued to, when the signature matches and the link has not expired
     */
    public Optional<String> verify(UUID fileId, String disposition, String exp, String actor, boolean suspectAck,
                                   String signature) {
        if (fileId == null || exp == null || actor == null || actor.isBlank() || signature == null) {
            return Optional.empty();
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(exp);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (clock.instant().getEpochSecond() >= expiresAt) {
            return Optional.empty();
        }
        String expected = hmac(fileId, normalizeDisposition(disposition), expiresAt, actor, suspectAck);
        boolean valid = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
        return valid ? Optional.of(actor) : Optional.empty();
    }

    private String hmac(UUID fileId, String disposition, long exp, String actor, boolean suspectAck) {
        String canonical = fileId.toString().toLowerCase(Locale.ROOT) + "|" + disposition + "|" + exp + "|" + actor
                + "|" + suspectAck;
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
