package ro.filegrid.security;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Read-only view of the authentication bound to the current thread (an HTTP request of the api). */
public final class CurrentAuthentication {

    private CurrentAuthentication() {
    }

    /** @return the authenticated caller, empty for anonymous requests and background threads */
    public static Optional<Authentication> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }

    /** @return realm roles of the caller (without the {@code ROLE_} prefix) */
    public static Set<String> roles() {
        Set<String> roles = new TreeSet<>();
        get().ifPresent(authentication -> {
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                String name = authority.getAuthority();
                if (name != null && name.startsWith(KeycloakJwtAuthenticationConverter.ROLE_PREFIX)) {
                    roles.add(name.substring(KeycloakJwtAuthenticationConverter.ROLE_PREFIX.length()));
                }
            }
        });
        return roles;
    }

    /** @return when the caller's bearer token expires (empty without a JWT or without {@code exp}) */
    public static Optional<Instant> tokenExpiresAt() {
        return get().filter(JwtAuthenticationToken.class::isInstance)
                .map(authentication -> ((JwtAuthenticationToken) authentication).getToken().getExpiresAt());
    }
}
