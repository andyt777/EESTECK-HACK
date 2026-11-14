package ro.filegrid.security;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Workers serve no HTTP API: any permission check reaching a worker-only
 * process is refused (fail closed).
 */
@Component
@Profile("!api")
public class DenyAllAccessPolicy implements AccessPolicy {

    @Override
    public boolean isAllowed(String actor, String permission) {
        return false;
    }
}
