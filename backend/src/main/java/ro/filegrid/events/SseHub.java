package ro.filegrid.events;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ro.filegrid.file.FileCategory;

/**
 * Open SSE connections and their filters. Events arrive from the fanout
 * exchange (any worker, any api instance) and are pushed to every matching
 * connection. A comment line is sent periodically so proxies keep idle
 * connections open.
 */
@Component
@Profile("api")
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    record Subscription(SseEmitter emitter, UUID fileId, FileCategory category) {
        boolean matches(LiveEvent event) {
            return (fileId == null || fileId.equals(event.fileId()))
                    && (category == null || category == event.category());
        }
    }

    public SseEmitter subscribe(UUID fileId, FileCategory category) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        Subscription subscription = new Subscription(emitter, fileId, category);
        subscriptions.add(subscription);
        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> {
            subscriptions.remove(subscription);
            emitter.complete();
        });
        emitter.onError(e -> subscriptions.remove(subscription));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            subscriptions.remove(subscription);
        }
        return emitter;
    }

    public void dispatch(LiveEvent event) {
        for (Subscription subscription : subscriptions) {
            if (!subscription.matches(event)) {
                continue;
            }
            try {
                subscription.emitter().send(SseEmitter.event()
                        .name("file-event")
                        .id(event.fileId() + ":" + event.timestamp().toEpochMilli())
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                subscriptions.remove(subscription);
                log.debug("SSE client disconnected: {}", e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 15_000)
    public void heartbeat() {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.emitter().send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                subscriptions.remove(subscription);
            }
        }
    }

    public int connections() {
        return subscriptions.size();
    }
}
