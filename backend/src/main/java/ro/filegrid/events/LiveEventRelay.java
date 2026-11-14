package ro.filegrid.events;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Relays every live event received from the fanout exchange to the SSE
 * connections of this api instance. The queue it listens on is declared in
 * {@link LiveEventTopology} (kept apart: a listener cannot reference a bean of
 * its own configuration class without a circular reference).
 */
@Component
@Profile("api")
public class LiveEventRelay {

    private final SseHub hub;

    public LiveEventRelay(SseHub hub) {
        this.hub = hub;
    }

    @RabbitListener(queues = "#{liveEventsQueue.name}")
    public void onEvent(LiveEvent event) {
        hub.dispatch(event);
    }
}
