package ro.filegrid.meter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ro.filegrid.file.FileCategory;
import ro.filegrid.messaging.Queues;

/**
 * Publishes readings to the meter queue as persistent messages and returns only
 * when RabbitMQ has confirmed every one of them (publisher confirms). Messages
 * are sent first and confirmations awaited together, so a batch of 500 costs
 * one round of waiting, not 500.
 */
@Component
public class MeterReadingPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 15;

    private final RabbitTemplate rabbit;

    public MeterReadingPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /**
     * @throws PublishException when at least one reading was not confirmed (the caller answers
     *                          503 and the client retries with the same Idempotency-Key)
     */
    public void publishAll(List<MeterReadingMessage> readings) {
        String routingKey = Queues.jobQueue(FileCategory.METER_READING);
        List<CorrelationData> pending = new ArrayList<>(readings.size());
        try {
            for (MeterReadingMessage reading : readings) {
                CorrelationData correlation = new CorrelationData(reading.readingId());
                rabbit.convertAndSend(Queues.JOBS_EXCHANGE, routingKey, reading, message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setType(MeterReadingMessage.TYPE);
                    message.getMessageProperties().setMessageId(reading.readingId());
                    return message;
                }, correlation);
                pending.add(correlation);
            }
            for (CorrelationData correlation : pending) {
                CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!confirm.isAck()) {
                    throw new PublishException("Broker refused reading " + correlation.getId() + ": " + confirm.getReason());
                }
                if (correlation.getReturned() != null) {
                    throw new PublishException("Reading " + correlation.getId() + " could not be routed to " + routingKey);
                }
            }
        } catch (PublishException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrupted while publishing readings", e);
        } catch (Exception e) {
            throw new PublishException("Could not publish readings: " + e.getMessage(), e);
        }
    }

    public static class PublishException extends RuntimeException {
        public PublishException(String message) {
            super(message);
        }

        public PublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
