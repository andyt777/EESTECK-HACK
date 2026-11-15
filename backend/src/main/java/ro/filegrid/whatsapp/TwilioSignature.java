package ro.filegrid.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Twilio webhook signature (https://www.twilio.com/docs/usage/security): Base64(HMAC-SHA1(authToken,
 * fullUrl + every POST parameter name and value, sorted by name, with no delimiters)).
 */
public final class TwilioSignature {

    private TwilioSignature() {
    }

    public static String compute(String authToken, String url, Map<String, String> params) {
        StringBuilder data = new StringBuilder(url);
        List<String> names = new ArrayList<>(params.keySet());
        Collections.sort(names); // String natural order = case-sensitive, like Unix sort
        for (String name : names) {
            String value = params.get(name);
            data.append(name).append(value == null ? "" : value);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /** Constant-time comparison; false for any missing input. */
    public static boolean valid(String authToken, String url, Map<String, String> params, String header) {
        if (authToken == null || authToken.isBlank() || url == null || header == null || header.isBlank()) {
            return false;
        }
        byte[] expected = compute(authToken, url, params).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, header.trim().getBytes(StandardCharsets.US_ASCII));
    }
}
