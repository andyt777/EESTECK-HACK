package ro.filegrid.worker;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ro.filegrid.file.FileCategory;

/**
 * The worker role. Validates {@code WORKER_CATEGORIES} at startup (a worker
 * without categories, or with an unknown one, refuses to start). The queue
 * consumers for these categories are added in Stage 4.
 */
@Component
@Profile("worker")
public class WorkerRole {

    private static final Logger log = LoggerFactory.getLogger(WorkerRole.class);

    private final Set<FileCategory> categories;
    private final int concurrency;

    public WorkerRole(WorkerProperties properties) {
        this.categories = properties.parsedCategories();
        if (categories.isEmpty()) {
            throw new IllegalStateException(
                    "The worker profile needs WORKER_CATEGORIES, for example METER_READING,FAULT_RECORD");
        }
        if (properties.concurrency() < 1) {
            throw new IllegalStateException("WORKER_CONCURRENCY must be at least 1");
        }
        this.concurrency = properties.concurrency();
        log.info("Worker started for categories {} with {} concurrent transfer(s) per category",
                categories, concurrency);
    }

    /** Transfers handled at the same time, per category. */
    public int concurrency() {
        return concurrency;
    }

    public Set<FileCategory> categories() {
        return categories;
    }
}
