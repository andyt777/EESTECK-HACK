package ro.filegrid.events;

import java.time.Duration;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ro.filegrid.file.FileCategory;
import ro.filegrid.passport.ProblemException;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.security.RolePermissions;
import ro.filegrid.security.TokenLifetime;
import ro.filegrid.text.Texts;

/**
 * {@code GET /api/events/stream}: live state changes as Server-Sent Events. Requires
 * {@code file:read}; the stream closes when the caller's token expires (the client reconnects).
 * Events carry no file names, so nothing personal is broadcast.
 */
@RestController
@Profile("api")
public class EventStreamController {

    private final SseHub hub;
    private final CurrentActorProvider actors;
    private final AccessPolicy access;
    private final Texts texts;

    public EventStreamController(SseHub hub, CurrentActorProvider actors, AccessPolicy access, Texts texts) {
        this.hub = hub;
        this.actors = actors;
        this.access = access;
        this.texts = texts;
    }

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) UUID fileId,
                             @RequestParam(required = false) FileCategory category) {
        if (!access.isAllowed(actors.currentActor(), RolePermissions.FILE_READ)) {
            throw new ProblemException(HttpStatus.FORBIDDEN, "forbidden", texts.get("auth.error.forbidden"));
        }
        Duration timeout = TokenLifetime.remaining(SseHub.EMITTER_TIMEOUT);
        return hub.subscribe(fileId, category, timeout);
    }
}
