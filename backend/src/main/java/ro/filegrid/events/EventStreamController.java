package ro.filegrid.events;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ro.filegrid.file.FileCategory;

/** {@code GET /api/events/stream}: live state changes as Server-Sent Events. */
@RestController
@Profile("api")
public class EventStreamController {

    private final SseHub hub;

    public EventStreamController(SseHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) UUID fileId,
                             @RequestParam(required = false) FileCategory category) {
        return hub.subscribe(fileId, category);
    }
}
