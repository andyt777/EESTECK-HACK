package ro.filegrid.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ro.filegrid.passport.ProblemException;
import ro.filegrid.text.Texts;

/** Shortcut for controllers: the current actor, or 403 (problem details) when the permission is missing. */
@Component
public class Authorizer {

    private final CurrentActorProvider actors;
    private final AccessPolicy access;
    private final Texts texts;

    public Authorizer(CurrentActorProvider actors, AccessPolicy access, Texts texts) {
        this.actors = actors;
        this.access = access;
        this.texts = texts;
    }

    /** @return the actor allowed to continue */
    public String require(String permission) {
        String actor = actors.currentActor();
        if (!access.isAllowed(actor, permission)) {
            throw new ProblemException(HttpStatus.FORBIDDEN, "forbidden", texts.get("auth.error.forbidden"));
        }
        return actor;
    }

    public boolean allows(String permission) {
        return access.isAllowed(actors.currentActor(), permission);
    }
}
