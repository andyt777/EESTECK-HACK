package ro.filegrid.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import ro.filegrid.messaging.TransferJob;

/**
 * Consumes transfer jobs with manual acknowledgement: a job is acknowledged
 * only after the worker has recorded its outcome (verified, PENDING or failed)
 * in meta-db. If the worker dies before that, RabbitMQ redelivers the job.
 *
 * <ul>
 *   <li>unreadable message → rejected without requeue (it ends in the category's DLQ);</li>
 *   <li>shutdown while transferring → requeued, another worker continues;</li>
 *   <li>unexpected error (meta-db unreachable, ...) → requeued after a pause, so nothing is lost.</li>
 * </ul>
 */
class TransferJobListener implements ChannelAwareMessageListener {

    private static final Logger log = LoggerFactory.getLogger(TransferJobListener.class);
    static final long REQUEUE_PAUSE_MILLIS = 5_000;

    private final TransferWorker worker;
    private final ObjectMapper json;

    TransferJobListener(TransferWorker worker, ObjectMapper json) {
        this.worker = worker;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        TransferJob job;
        try {
            job = parse(message.getBody());
        } catch (IOException | RuntimeException e) {
            log.error("Unreadable transfer job sent to the dead-letter queue: {}", e.getMessage());
            channel.basicReject(tag, false);
            return;
        }
        if (job.traceId() != null) {
            MDC.put("traceId", job.traceId());
        }
        MDC.put("fileId", job.fileId().toString());
        try {
            TransferWorker.Result result = worker.process(job);
            log.debug("Job for file {} finished: {}", job.fileId(), result);
            channel.basicAck(tag, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Worker stopping: job for file {} returned to the queue", job.fileId());
            channel.basicNack(tag, false, true);
        } catch (RuntimeException e) {
            log.error("Job for file {} failed unexpectedly; requeued in {} ms: {}", job.fileId(),
                    REQUEUE_PAUSE_MILLIS, e.getMessage(), e);
            pause();
            channel.basicNack(tag, false, true);
        } finally {
            MDC.remove("traceId");
            MDC.remove("fileId");
        }
    }

    /**
     * Reads the job. A body that is a JSON string holding the job (a producer
     * that serialised an already serialised job) is accepted as well.
     */
    TransferJob parse(byte[] body) throws IOException {
        JsonNode node = json.readTree(body);
        if (node != null && node.isTextual()) {
            node = json.readTree(node.asText());
        }
        if (node == null || !node.isObject()) {
            throw new IOException("A transfer job must be a JSON object");
        }
        TransferJob job = json.treeToValue(node, TransferJob.class);
        if (job.fileId() == null) {
            throw new IOException("A transfer job needs a fileId");
        }
        return job;
    }

    private static void pause() {
        try {
            Thread.sleep(REQUEUE_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
