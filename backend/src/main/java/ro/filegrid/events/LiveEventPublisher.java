package ro.filegrid.events;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.stereotype.Component;
import ro.filegrid.messaging.Queues;

/**
 * Broadcasts live events on the fanout exchange. Best effort: the audit trail
 * in meta-db is the source of truth, so a broker hiccup never fails a transfer.
 *
 * <p>RabbitMQ keeps the order of messages only within one channel. Events go
 * through a connection of their own with exactly one channel, so the events of
 * this process reach the browsers in the order the states changed
 * (RECEIVED before ANALYZING, every progress event in sequence). It is not a
 * Spring bean, so the auto-configured RabbitTemplate (jobs, with publisher
 * confirms) is left untouched.
 */
@Component
public class LiveEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LiveEventPublisher.class);

    private final CachingConnectionFactory connection;
    private final RabbitTemplate rabbit;

    public LiveEventPublisher(RabbitProperties properties, MessageConverter converter) {
        connection = new CachingConnectionFactory(properties.determineHost(), properties.determinePort());
        connection.setUsername(properties.determineUsername());
        connection.setPassword(properties.determinePassword());
        if (properties.determineVirtualHost() != null) {
            connection.setVirtualHost(properties.determineVirtualHost());
        }
        connection.setChannelCacheSize(1);
        connection.setChannelCheckoutTimeout(10_000);
        connection.setConnectionNameStrategy(factory -> "filegrid-live-events");
        rabbit = new RabbitTemplate(connection);
        rabbit.setMessageConverter(converter);
    }

    public void publish(LiveEvent event) {
        try {
            rabbit.convertAndSend(Queues.EVENTS_EXCHANGE, "", event);
        } catch (RuntimeException e) {
            log.warn("Live event {} for file {} not broadcast: {}", event.status(), event.fileId(), e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        connection.destroy();
    }
}
