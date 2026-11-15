package ro.filegrid.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContentLinkSignerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID FILE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final ContentLinkSigner signer = new ContentLinkSigner(properties(new byte[32]));

    private static SecurityProperties properties(byte[] key) {
        return new SecurityProperties(List.of("https://localhost/auth/realms/filegrid"), "", "", "filegrid-api",
                Base64.getEncoder().encodeToString(key));
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private ContentLinkSigner.Grant verify(ContentLinkSigner verifier, UUID file, Map<String, String> q, Instant at) {
        return verifier.verify(file, q.get("disposition"), q.get("exp"), q.get("act"), q.get("ack"), q.get("sig"), at);
    }

    @Test
    void validLinkGrantsAccessToThatFileForThatActor() {
        var link = signer.sign(FILE, "inline", "connections.demo", true, NOW);
        assertThat(link.url()).startsWith("/api/files/" + FILE + "/content?");
        assertThat(link.expiresAt()).isEqualTo(NOW.plus(ContentLinkSigner.INLINE_TTL));

        var grant = verify(signer, FILE, query(link.url()), NOW.plusSeconds(60));
        assertThat(grant).isEqualTo(new ContentLinkSigner.Grant(FILE, "inline", "connections.demo", true));
    }

    @Test
    void attachmentLinksExpireSooner() {
        var link = signer.sign(FILE, "attachment", "admin.demo", false, NOW);
        assertThat(link.expiresAt()).isEqualTo(NOW.plus(ContentLinkSigner.ATTACHMENT_TTL));
    }

    @Test
    void alteredLinkIsRejected() {
        var q = query(signer.sign(FILE, "attachment", "field.demo", false, NOW).url());

        var otherActor = new HashMap<>(q);
        otherActor.put("act", "admin.demo");
        assertThat(verify(signer, FILE, otherActor, NOW)).isNull();

        var acknowledged = new HashMap<>(q);
        acknowledged.put("ack", "1");
        assertThat(verify(signer, FILE, acknowledged, NOW)).isNull();

        var inline = new HashMap<>(q);
        inline.put("disposition", "inline");
        assertThat(verify(signer, FILE, inline, NOW)).isNull();

        var later = new HashMap<>(q);
        later.put("exp", String.valueOf(Long.parseLong(q.get("exp")) + 3600));
        assertThat(verify(signer, FILE, later, NOW)).isNull();

        var badSig = new HashMap<>(q);
        badSig.put("sig", "A" + q.get("sig").substring(1));
        assertThat(verify(signer, FILE, badSig, NOW)).isNull();
    }

    @Test
    void expiredLinkIsRejected() {
        var link = signer.sign(FILE, "attachment", "field.demo", false, NOW);
        var q = query(link.url());
        assertThat(verify(signer, FILE, q, link.expiresAt())).isNotNull();
        assertThat(verify(signer, FILE, q, link.expiresAt().plusSeconds(1))).isNull();
    }

    @Test
    void linkForAnotherFileIsRejected() {
        var q = query(signer.sign(FILE, "inline", "field.demo", false, NOW).url());
        assertThat(verify(signer, UUID.randomUUID(), q, NOW)).isNull();
    }

    @Test
    void linkSignedWithAnotherKeyIsRejected() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        var other = new ContentLinkSigner(properties(otherKey));
        var q = query(other.sign(FILE, "inline", "field.demo", false, NOW).url());
        assertThat(verify(signer, FILE, q, NOW)).isNull();
    }

    @Test
    void missingOrMalformedParametersAreRejected() {
        assertThat(signer.verify(FILE, "inline", null, "a", "0", "sig", NOW)).isNull();
        assertThat(signer.verify(FILE, "inline", "soon", "a", "0", "sig", NOW)).isNull();
        assertThat(signer.verify(null, "inline", "1", "a", "0", "sig", NOW)).isNull();
    }
}
