package ro.filegrid.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stage 11: JWT validation (Keycloak, realm {@code filegrid}) and signed content links.
 *
 * @param issuers     accepted {@code iss} values: the public one (browser tokens) and the
 *                    internal backchannel one (service tokens fetched inside the Docker network)
 * @param jwkSetUri   where the realm signing keys are read (internal network)
 * @param publicKey   alternative to {@code jwkSetUri}: a fixed RSA public key (base64 X.509), used by tests
 * @param audience    required {@code aud} value
 * @param linkKey     HMAC key (base64, 256 bit) for short-lived signed download/preview links
 */
@ConfigurationProperties("filegrid.security")
public record SecurityProperties(
        @DefaultValue({"https://localhost/auth/realms/filegrid", "http://keycloak:8080/auth/realms/filegrid"})
        List<String> issuers,
        @DefaultValue("http://keycloak:8080/auth/realms/filegrid/protocol/openid-connect/certs") String jwkSetUri,
        @DefaultValue("") String publicKey,
        @DefaultValue("filegrid-api") String audience,
        @DefaultValue("") String linkKey) {
}
