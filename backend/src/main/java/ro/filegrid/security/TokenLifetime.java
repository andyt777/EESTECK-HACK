package ro.filegrid.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * How long a long-lived response (SSE) may stay open: never past the expiry of the token that
 * authorized it. The browser then reconnects with a fresh token, so a revoked or downgraded
 * user stops receiving events within one token lifetime.
 */
public final class TokenLifetime {

    private static final Duration MINIMUM = Duration.ofSeconds(15);

    private TokenLifetime() {
    }

    public static Duration remaining(Duration fallback) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && jwt.getToken().getExpiresAt() != null) {
            Duration left = Duration.between(Instant.now(), jwt.getToken().getExpiresAt());
            if (left.compareTo(MINIMUM) < 0) {
                return MINIMUM;
            }
            return left.compareTo(fallback) < 0 ? left : fallback;
        }
        return fallback;
    }
}
