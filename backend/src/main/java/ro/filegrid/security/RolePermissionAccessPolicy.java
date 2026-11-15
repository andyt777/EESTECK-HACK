package ro.filegrid.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single authorization decision point (docs/AUTH-DESIGN.md §3): the roles of the
 * authenticated caller are mapped to permissions by {@link RolePermissions}. Without an
 * authenticated caller (or for an actor other than the caller) everything is denied, so a
 * check that is reached outside an HTTP request fails closed. Denials are logged and counted.
 */
@Component
public class RolePermissionAccessPolicy implements AccessPolicy {

    public static final String ROLE_PREFIX = "ROLE_";
    public static final String PERMISSION_PREFIX = "PERM_";
    private static final Logger log = LoggerFactory.getLogger(RolePermissionAccessPolicy.class);

    private final ObjectProvider<MeterRegistry> meters;

    public RolePermissionAccessPolicy(ObjectProvider<MeterRegistry> meters) {
        this.meters = meters;
    }

    @Override
    public boolean isAllowed(String actor, String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getName().equals(actor) && permissions(auth).contains(permission);
        if (!allowed) {
            log.info("Access denied: actor={} permission={}", actor, permission);
            MeterRegistry registry = meters.getIfAvailable();
            if (registry != null) {
                registry.counter("filegrid_access_denied_total", "permission", permission).increment();
            }
        }
        return allowed;
    }

    /** Permissions of the current caller (empty when nobody is authenticated). */
    public static Set<String> currentPermissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Set.of();
        }
        return permissions(auth);
    }

    /**
     * Role permissions plus permissions granted directly as {@code PERM_*} authorities
     * (a signed content link carries the permissions checked when the link was issued).
     */
    static Set<String> permissions(Authentication auth) {
        Set<String> permissions = new java.util.TreeSet<>(RolePermissions.of(roles(auth)));
        auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(PERMISSION_PREFIX)).map(a -> a.substring(PERMISSION_PREFIX.length()))
                .forEach(permissions::add);
        return permissions;
    }

    /** Realm roles of the caller, without the {@code ROLE_} prefix. */
    public static Set<String> roles(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(ROLE_PREFIX)).map(a -> a.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }
}
