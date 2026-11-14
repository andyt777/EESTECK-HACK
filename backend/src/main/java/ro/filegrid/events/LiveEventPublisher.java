package ro.filegrid.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ro.filegrid.messaging.Queues;

/**
 * Broadcasts live events on the fanout exchange. Best effort: the audit trail
 * in meta-db is the source of truth, so a broker hiccup never fails a transfer.
 */
@Component
public class LiveEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LiveEventPublisher.class);

    private final RabbitTemplate rabbit;

    public LiveEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void publish(LiveEvent event) {
        try {
            rabbit.convertAndSend(Queues.EVENTS_EXCHANGE, "", event);
        } catch (RuntimeException e) {
            log.warn("Live event {} for file {} not broadcast: {}", event.status(), event.fileId(), e.getMessage());
        }
    }
}
