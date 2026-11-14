package ro.filegrid.messaging;

import ro.filegrid.file.FileCategory;

/**
 * Names of the RabbitMQ exchanges and queues. One durable job queue per
 * category (bulkheading: a flood of meter readings never delays a video), each
 * with its own dead-letter queue.
 */
public final class Queues {

    /** Direct exchange receiving transfer jobs; the routing key is the queue name. */
    public static final String JOBS_EXCHANGE = "filegrid.jobs";

    /** Direct exchange receiving rejected jobs; the routing key is the dead-letter queue name. */
    public static final String DEAD_LETTER_EXCHANGE = "filegrid.dlx";

    /** Fanout exchange carrying every state change of every file (live view). */
    public static final String EVENTS_EXCHANGE = "filegrid.events";

    private Queues() {
    }

    /** {@code filegrid.jobs.meter|fault|document|video}. */
    public static String jobQueue(FileCategory category) {
        return JOBS_EXCHANGE + "." + shortName(category);
    }

    public static String deadLetterQueue(FileCategory category) {
        return jobQueue(category) + ".dlq";
    }

    static String shortName(FileCategory category) {
        return switch (category) {
            case METER_READING -> "meter";
            case FAULT_RECORD -> "fault";
            case CONNECTION_DOCUMENT -> "document";
            case DRONE_VIDEO -> "video";
        };
    }
}
