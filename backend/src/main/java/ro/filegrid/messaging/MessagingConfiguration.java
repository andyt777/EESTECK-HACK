package ro.filegrid.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.filegrid.file.FileCategory;

/**
 * RabbitMQ topology, declared by every process at startup (declarations are
 * idempotent): durable job queues per category with a dead-letter queue each,
 * and the fanout exchange of live events.
 */
@Configuration
public class MessagingConfiguration {

    @Bean
    public Declarables jobTopology() {
        DirectExchange jobs = new DirectExchange(Queues.JOBS_EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(Queues.DEAD_LETTER_EXCHANGE, true, false);
        List<org.springframework.amqp.core.Declarable> declarables = new ArrayList<>(List.of(jobs, dlx));
        for (FileCategory category : FileCategory.values()) {
            String queueName = Queues.jobQueue(category);
            String dlqName = Queues.deadLetterQueue(category);
            Queue queue = QueueBuilder.durable(queueName)
                    .deadLetterExchange(Queues.DEAD_LETTER_EXCHANGE)
                    .deadLetterRoutingKey(dlqName)
                    .build();
            Queue dlq = QueueBuilder.durable(dlqName).build();
            Binding binding = BindingBuilder.bind(queue).to(jobs).with(queueName);
            Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(dlqName);
            declarables.addAll(List.of(queue, dlq, binding, dlqBinding));
        }
        return new Declarables(declarables);
    }

    @Bean
    public FanoutExchange eventsExchange() {
        return new FanoutExchange(Queues.EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
