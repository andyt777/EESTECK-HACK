package ro.filegrid.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Role-based decisions for the api: the permissions of the authenticated caller
 * come from its realm roles ({@link RolePermissions}). A check about another
 * actor than the authenticated one, or without an authentication, is denied.
 * Every denial is logged and counted ({@code filegrid_access_denied_total}).
 */
@Component
@Profile("api")
public class RolePermissionAccessPolicy implements AccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionAccessPolicy.class);
    static final String DENIED_METRIC = "filegrid_access_denied_total";

    private final MeterRegistry registry;

    public RolePermissionAccessPolicy(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean isAllowed(String actor, String permission) {
        Authentication authentication = CurrentAuthentication.get().orElse(null);
        if (authentication == null) {
            return deny(actor, permission, "unauthenticated");
        }
        if (actor == null || !actor.equals(authentication.getName())) {
            return deny(actor, permission, "actor-mismatch");
        }
        Set<String> permissions = RolePermissions.permissionsOf(CurrentAuthentication.roles());
        if (permissions.contains(permission)) {
            return true;
        }
        return deny(actor, permission, "missing-permission");
    }

    private boolean deny(String actor, String permission, String reason) {
        log.info("Access denied: actor={} permission={} reason={}", actor, permission, reason);
        Counter.builder(DENIED_METRIC)
                .description("Authorization checks refused by the access policy")
                .tag("permission", permission == null ? "none" : permission)
                .register(registry)
                .increment();
        return false;
    }
}
