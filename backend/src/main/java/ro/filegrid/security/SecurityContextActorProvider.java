package ro.filegrid.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The actor is the authenticated principal ({@code preferred_username} of the token, for
 * example {@code dispatcher.demo} or {@code service-account-filegrid-simulator}). Work that
 * does not come from an HTTP request (schedulers, workers, recovery) runs as {@code system}.
 */
@Component
public class SecurityContextActorProvider implements CurrentActorProvider {

    public static final String SYSTEM = "system";

    @Override
    public String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return SYSTEM;
        }
        return auth.getName();
    }
}
