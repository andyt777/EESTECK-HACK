package ro.filegrid.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.filegrid.file.FileCategory;
import ro.filegrid.messaging.Queues;

/**
 * Per-category gauges that prove the bulkhead (Stage 8):
 * {@code filegrid_queue_depth{category}}, {@code filegrid_queue_consumers{category}}
 * (one queue and its own workers per category) and
 * {@code filegrid_pending_files{category}} (files kept in staging until a storage is back).
 *
 * <p>Runs only in the api role, so that one process polls the broker and meta-db.
 */
@Component
@Profile("api")
public class QueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);

    private final AmqpAdmin admin;
    private final JdbcTemplate metaDb;
    private final Map<FileCategory, AtomicLong> depth = new EnumMap<>(FileCategory.class);
    private final Map<FileCategory, AtomicLong> consumers = new EnumMap<>(FileCategory.class);
    private final Map<FileCategory, AtomicLong> pending = new EnumMap<>(FileCategory.class);

    public QueueMetrics(AmqpAdmin admin, JdbcTemplate metaDb, MeterRegistry registry) {
        this.admin = admin;
        this.metaDb = metaDb;
        for (FileCategory category : FileCategory.values()) {
            depth.put(category, gauge(registry, "filegrid.queue.depth",
                    "Messages waiting in the job queue of the category", category));
            consumers.put(category, gauge(registry, "filegrid.queue.consumers",
                    "Worker consumers attached to the job queue of the category", category));
            pending.put(category, gauge(registry, "filegrid.pending.files",
                    "Files in PENDING (kept in staging, retried automatically)", category));
        }
    }

    private static AtomicLong gauge(MeterRegistry registry, String name, String description, FileCategory category) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::get)
                .description(description)
                .tag("category", category.name())
                .register(registry);
        return value;
    }

    @Scheduled(fixedDelayString = "${filegrid.observability.queue-poll-interval:15s}", initialDelayString = "5s")
    public void refresh() {
        for (FileCategory category : FileCategory.values()) {
            try {
                QueueInformation info = admin.getQueueInfo(Queues.jobQueue(category));
                depth.get(category).set(info == null ? 0 : info.getMessageCount());
                consumers.get(category).set(info == null ? 0 : info.getConsumerCount());
            } catch (RuntimeException e) {
                log.debug("Queue {} could not be inspected: {}", Queues.jobQueue(category), e.getMessage());
            }
        }
        try {
            Map<FileCategory, Long> counts = new EnumMap<>(FileCategory.class);
            metaDb.query("SELECT category, count(*) FROM file_record WHERE status = 'PENDING' GROUP BY category",
                    rs -> {
                        counts.put(FileCategory.valueOf(rs.getString(1)), rs.getLong(2));
                    });
            for (FileCategory category : FileCategory.values()) {
                pending.get(category).set(counts.getOrDefault(category, 0L));
            }
        } catch (RuntimeException e) {
            log.debug("Pending files could not be counted: {}", e.getMessage());
        }
    }
}
