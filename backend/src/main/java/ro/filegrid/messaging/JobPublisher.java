package ro.filegrid.messaging;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes transfer jobs as persistent messages and waits for the broker's
 * confirmation: a job counts as queued only once RabbitMQ has taken
 * responsibility for it.
 */
@Component
public class JobPublisher {

    private static final Logger log = LoggerFactory.getLogger(JobPublisher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 10;

    private final RabbitTemplate rabbit;

    public JobPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /**
     * @throws JobPublishException when the broker does not confirm the message
     */
    public void publish(TransferJob job) {
        String routingKey = Queues.jobQueue(job.category());
        CorrelationData correlation = new CorrelationData(job.fileId() + ":" + System.nanoTime());
        try {
            rabbit.convertAndSend(Queues.JOBS_EXCHANGE, routingKey, job, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setMessageId(job.fileId().toString());
                if (job.traceId() != null) {
                    message.getMessageProperties().setHeader("traceId", job.traceId());
                }
                return message;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new JobPublishException("Broker refused job for file " + job.fileId() + ": " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new JobPublishException("Job for file " + job.fileId() + " could not be routed to " + routingKey);
            }
            log.info("Job for file {} queued on {}", job.fileId(), routingKey);
        } catch (JobPublishException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobPublishException("Interrupted while publishing job for " + job.fileId(), e);
        } catch (Exception e) {
            throw new JobPublishException("Could not publish job for file " + job.fileId() + ": " + e.getMessage(), e);
        }
    }

    public static class JobPublishException extends RuntimeException {
        public JobPublishException(String message) {
            super(message);
        }

        public JobPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
