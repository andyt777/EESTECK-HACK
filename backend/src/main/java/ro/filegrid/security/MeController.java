package ro.filegrid.security;

import java.time.Instant;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/me}: who the caller is and what they may do. The frontend uses it to show or
 * hide actions; the intelligence service uses it to authorize its own endpoints with the
 * caller's token.
 */
@RestController
@Profile("api")
public class MeController {

    private final CurrentActorProvider actors;

    public MeController(CurrentActorProvider actors) {
        this.actors = actors;
    }

    public record Me(String actor, String name, Set<String> roles, Set<String> permissions, Instant expiresAt) {
    }

    @GetMapping("/me")
    public Me me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String name = null;
        Instant expires = null;
        if (auth instanceof JwtAuthenticationToken jwt) {
            name = jwt.getToken().getClaimAsString("name");
            expires = jwt.getToken().getExpiresAt();
        }
        return new Me(actors.currentActor(), name, RolePermissionAccessPolicy.roles(auth),
                RolePermissionAccessPolicy.currentPermissions(), expires);
    }
}
