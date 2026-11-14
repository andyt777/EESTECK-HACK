package ro.filegrid.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The actor is the authenticated caller ({@code preferred_username} of the
 * bearer token). Without an authentication (schedulers, queue listeners,
 * {@code @Async} work) the actor is {@value #SYSTEM}; asynchronous work started
 * by a request must carry the actor captured on the request thread instead.
 */
@Component
public class JwtCurrentActorProvider implements CurrentActorProvider {

    public static final String SYSTEM = "system";

    @Override
    public String currentActor() {
        return CurrentAuthentication.get().map(Authentication::getName).orElse(SYSTEM);
    }
}
