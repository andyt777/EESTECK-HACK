package ro.filegrid.security;

import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/me}: who the caller is and what it may do. Used by the
 * frontend (menus, guards) and by the intelligence service (it forwards the
 * user's bearer token and authorizes on the returned permissions).
 */
@RestController
@Profile("api")
public class MeController {

    /** @param roles known realm roles of the caller; @param permissions derived from them */
    public record Me(String actor, List<String> roles, List<String> permissions) {
    }

    private final CurrentActorProvider actors;

    public MeController(CurrentActorProvider actors) {
        this.actors = actors;
    }

    @GetMapping("/me")
    public Me me() {
        Set<String> roles = CurrentAuthentication.roles();
        roles.retainAll(RolePermissions.knownRoles());
        return new Me(actors.currentActor(), List.copyOf(roles),
                List.copyOf(RolePermissions.permissionsOf(roles)));
    }
}
