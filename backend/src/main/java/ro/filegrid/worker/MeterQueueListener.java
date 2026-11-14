package ro.filegrid.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareBatchMessageListener;
import ro.filegrid.meter.MeterReadingMessage;
import ro.filegrid.meter.MeterReadingWriter;
import ro.filegrid.meter.MeterReadings;

/**
 * Consumer of {@code filegrid.jobs.meter}. The queue carries two kinds of
 * messages: meter readings from the simulator / API (type
 * {@link MeterReadingMessage#TYPE}) and ordinary transfer jobs for meter
 * files uploaded from the UI.
 *
 * <p>Readings arrive in batches of up to ~200; each batch is written in one
 * store-db transaction and every message of it is acknowledged only after
 * that commit. If the write fails, the whole batch is requeued.
 */
class MeterQueueListener implements ChannelAwareBatchMessageListener {

    private static final Logger log = LoggerFactory.getLogger(MeterQueueListener.class);

    private final MeterReadingWriter writer;
    private final TransferJobListener transfers;
    private final ObjectMapper json;

    MeterQueueListener(MeterReadingWriter writer, TransferJobListener transfers, ObjectMapper json) {
        this.writer = writer;
        this.transfers = transfers;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        onMessageBatch(List.of(message), channel);
    }

    @Override
    public void onMessageBatch(List<Message> messages, Channel channel) {
        List<MeterReadingMessage> readings = new ArrayList<>();
        List<Long> tags = new ArrayList<>();
        try {
            for (Message message : messages) {
                long tag = message.getMessageProperties().getDeliveryTag();
                if (!MeterReadingMessage.TYPE.equals(message.getMessageProperties().getType())) {
                    transfers.onMessage(message, channel);
                    continue;
                }
                MeterReadingMessage reading = parse(message);
                if (reading == null) {
                    channel.basicReject(tag, false);
                    continue;
                }
                readings.add(reading);
                tags.add(tag);
            }
            if (readings.isEmpty()) {
                return;
            }
            writer.write(readings);
            for (long tag : tags) {
                channel.basicAck(tag, false);
            }
        } catch (IOException e) {
            log.error("Meter batch could not be acknowledged; RabbitMQ will redeliver it: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Meter batch of {} reading(s) not stored, requeued in {} ms: {}", readings.size(),
                    TransferJobListener.REQUEUE_PAUSE_MILLIS, e.getMessage());
            pause();
            for (long tag : tags) {
                try {
                    channel.basicNack(tag, false, true);
                } catch (IOException nackFailed) {
                    // the channel is gone: unacknowledged messages are redelivered anyway
                }
            }
        }
    }

    /** The reading, or {@code null} when the message is unreadable or was altered in flight. */
    private MeterReadingMessage parse(Message message) {
        try {
            MeterReadingMessage reading = json.readValue(message.getBody(), MeterReadingMessage.class);
            if (reading.readingId() == null || reading.content() == null || reading.sha256() == null
                    || reading.meterId() == null || reading.periodStart() == null || reading.periodEnd() == null) {
                log.error("Incomplete meter reading sent to the dead-letter queue");
                return null;
            }
            if (!MeterReadings.sha256(reading.content()).equals(reading.sha256())
                    || !MeterReadings.readingId(reading.meterId(), reading.periodStart()).equals(reading.readingId())) {
                log.error("Meter reading {} changed in flight (hash or id mismatch); sent to the dead-letter queue",
                        reading.readingId());
                return null;
            }
            return reading;
        } catch (IOException e) {
            log.error("Unreadable meter reading sent to the dead-letter queue: {}", e.getMessage());
            return null;
        }
    }

    private static void pause() {
        try {
            Thread.sleep(TransferJobListener.REQUEUE_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
