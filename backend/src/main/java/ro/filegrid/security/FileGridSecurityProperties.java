package ro.filegrid.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * FileGrid-specific security settings ({@code filegrid.security.*}). The JWT
 * issuer, JWK set and audience use the standard
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties.
 *
 * @param additionalIssuers issuers accepted besides {@code issuer-uri}, for example the
 *                          internal Keycloak URL if tokens fetched over the backchannel
 *                          carry it (empty by default: only the public issuer is accepted)
 * @param linkKey           HMAC-SHA256 key of signed content links ({@code FILEGRID_LINK_KEY})
 * @param linkTtlAttachment lifetime of a download link
 * @param linkTtlInline     lifetime of an inline (preview / media) link
 */
@ConfigurationProperties("filegrid.security")
public record FileGridSecurityProperties(
        @DefaultValue List<String> additionalIssuers,
        String linkKey,
        @DefaultValue("5m") Duration linkTtlAttachment,
        @DefaultValue("60m") Duration linkTtlInline) {
}
