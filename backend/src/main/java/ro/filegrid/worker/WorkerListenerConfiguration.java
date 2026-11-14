package ro.filegrid.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ro.filegrid.file.FileCategory;
import ro.filegrid.meter.MeterReadingWriter;
import ro.filegrid.messaging.Queues;

/**
 * One listener container per category in {@code WORKER_CATEGORIES}, each with
 * its own consumers (bulkhead: a backlog of videos never occupies the threads
 * that move fault records). Concurrency and prefetch come from
 * {@link WorkerProperties}, per category.
 */
@Component
@Profile("worker")
public class WorkerListenerConfiguration implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerListenerConfiguration.class);

    private final List<SimpleMessageListenerContainer> containers = new ArrayList<>();
    private volatile boolean running;

    public WorkerListenerConfiguration(WorkerRole role, WorkerProperties properties, ConnectionFactory connections,
                                       TransferWorker worker, MeterReadingWriter meterWriter, ObjectMapper json) {
        TransferJobListener listener = new TransferJobListener(worker, json);
        for (FileCategory category : role.categories()) {
            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connections);
            container.setQueueNames(Queues.jobQueue(category));
            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            int concurrency = Math.max(1, properties.concurrencyFor(category));
            container.setConcurrentConsumers(concurrency);
            container.setMaxConcurrentConsumers(concurrency);
            int prefetch = Math.max(1, properties.prefetchFor(category));
            if (category == FileCategory.METER_READING) {
                // Readings are small and many: consumed in batches, one store-db transaction each.
                int batchSize = properties.meterBatchSize();
                prefetch = Math.max(prefetch, batchSize + batchSize / 4);
                container.setConsumerBatchEnabled(true);
                container.setDeBatchingEnabled(true);
                container.setBatchSize(batchSize);
                container.setReceiveTimeout(500);
                container.setMessageListener(new MeterQueueListener(meterWriter, listener, json));
            } else {
                container.setMessageListener(listener);
            }
            container.setPrefetchCount(prefetch);
            container.setDefaultRequeueRejected(false);
            container.setMissingQueuesFatal(false);
            // A video transfer may take minutes: wait for it on shutdown instead of cutting it.
            container.setShutdownTimeout(30_000);
            container.setConsumerTagStrategy(queue -> "worker-" + queue + "-" + java.util.UUID.randomUUID());
            container.setBeanName("worker-" + category.name());
            container.afterPropertiesSet();
            containers.add(container);
            log.info("Consuming {} with {} consumer(s), prefetch {}", Queues.jobQueue(category), concurrency,
                    prefetch);
        }
    }

    @Override
    public void start() {
        containers.forEach(SimpleMessageListenerContainer::start);
        running = true;
    }

    @Override
    public void stop() {
        containers.forEach(SimpleMessageListenerContainer::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Started after everything else (schema, policy) is in place. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    List<SimpleMessageListenerContainer> containers() {
        return containers;
    }
}
