package ro.filegrid.events;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Each api instance listens on its own exclusive, auto-deleted queue bound to
 * the fanout exchange, so every instance sees every event and nothing piles up
 * when an instance stops.
 */
@Configuration
@Profile("api")
public class LiveEventTopology {

    @Bean
    public Queue liveEventsQueue() {
        return new AnonymousQueue();
    }

    @Bean
    public Binding liveEventsBinding(Queue liveEventsQueue, FanoutExchange eventsExchange) {
        return BindingBuilder.bind(liveEventsQueue).to(eventsExchange);
    }
}
