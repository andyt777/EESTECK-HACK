package ro.filegrid.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Access tokens for tests, shaped like Keycloak's (realm_access.roles, preferred_username,
 * aud filegrid-api), signed with a key pair generated once per JVM. The public key is given to
 * the application by {@link TestSecurityContextCustomizerFactory}; the private key never leaves
 * the test process.
 */
public final class TestTokens {

    public static final String ISSUER = "https://localhost/auth/realms/filegrid";
    /** Default identity of the integration tests: an administrator that may also ingest meters and alerts. */
    public static final String ADMIN = "test-admin";

    private static final KeyPair KEYS = generate();
    private static final String ADMIN_TOKEN = token(ADMIN, Duration.ofHours(6), "admin", "svc-meter-ingest",
            "svc-alert-ingest");

    private TestTokens() {
    }

    public static String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());
    }

    /** Token of the default test administrator. */
    public static String admin() {
        return ADMIN_TOKEN;
    }

    public static String bearerAdmin() {
        return "Bearer " + ADMIN_TOKEN;
    }

    public static String token(String username, String... roles) {
        return token(username, Duration.ofMinutes(5), roles);
    }

    public static String token(String username, Duration lifetime, String... roles) {
        return sign(username, Instant.now().plus(lifetime), ISSUER, "filegrid-api", roles);
    }

    public static String sign(String username, Instant expiresAt, String issuer, String audience, String... roles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.nameUUIDFromBytes(username.getBytes()).toString())
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(expiresAt))
                .claim("preferred_username", username)
                .claim("name", username)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
