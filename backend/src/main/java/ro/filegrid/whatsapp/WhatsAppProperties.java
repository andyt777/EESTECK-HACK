package ro.filegrid.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PPCastor on WhatsApp ({@code filegrid.whatsapp.*}). Secrets come from the environment only.
 *
 * @param webhookUrl      the public URL Twilio calls (exactly as configured in Twilio), used for the signature
 * @param tokenUrl        Keycloak token endpoint (client credentials)
 * @param clientId        Keycloak client of the WhatsApp channel (role {@code svc-whatsapp})
 * @param clientSecret    its secret
 * @param intelligenceUrl base URL of the intelligence service ({@code .../api/intelligence})
 * @param mediaMaxBytes   cap for a downloaded voice note or photo
 * @param workers         threads answering messages (LLM answers can take longer than Twilio waits)
 */
@ConfigurationProperties(prefix = "filegrid.whatsapp")
public record WhatsAppProperties(
        @DefaultValue("") String webhookUrl,
        @DefaultValue("http://keycloak:8080/auth/realms/filegrid/protocol/openid-connect/token") String tokenUrl,
        @DefaultValue("filegrid-whatsapp") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("http://intelligence:8000/api/intelligence") String intelligenceUrl,
        @DefaultValue("16777216") long mediaMaxBytes,
        @DefaultValue("4") int workers) {
}
