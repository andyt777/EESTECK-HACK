package ro.filegrid.security;

import org.springframework.stereotype.Component;

/** Placeholder until authentication exists: everything is allowed. */
@Component
public class PermissiveAccessPolicy implements AccessPolicy {

    @Override
    public boolean isAllowed(String actor, String permission) {
        return true;
    }
}
