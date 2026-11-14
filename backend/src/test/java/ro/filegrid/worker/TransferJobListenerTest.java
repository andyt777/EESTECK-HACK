package ro.filegrid.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import ro.filegrid.file.FileCategory;
import ro.filegrid.messaging.TransferJob;
import ro.filegrid.storage.StorageType;

class TransferJobListenerTest {

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final TransferWorker worker = mock(TransferWorker.class);
    private final Channel channel = mock(Channel.class);
    private final TransferJobListener listener = new TransferJobListener(worker, json);

    private final TransferJob job = new TransferJob(UUID.randomUUID(), FileCategory.DRONE_VIDEO, "/data/staging/x",
            10, "abc", StorageType.S3, "videos/2026/11/14/x", "trace", Instant.parse("2026-11-14T10:00:00Z"));

    @Test
    void acceptsAJobObjectAndAJobSerialisedTwice() throws IOException {
        byte[] direct = json.writeValueAsBytes(job);
        byte[] twice = json.writeValueAsBytes(json.writeValueAsString(job));
        assertThat(listener.parse(direct)).isEqualTo(job);
        assertThat(listener.parse(twice)).isEqualTo(job);
    }

    @Test
    void rejectsSomethingThatIsNotAJob() {
        assertThatThrownBy(() -> listener.parse("[1,2]".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> listener.parse("{}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);
    }

    @Test
    void acknowledgesOnlyAfterTheWorkerRecordedTheOutcome() throws Exception {
        when(worker.process(any())).thenReturn(TransferWorker.Result.PENDING);
        listener.onMessage(message(json.writeValueAsBytes(job), 7), channel);
        verify(worker).process(job);
        verify(channel).basicAck(7, false);
    }

    @Test
    void sendsAnUnreadableMessageToTheDeadLetterQueue() throws Exception {
        listener.onMessage(message("not json".getBytes(StandardCharsets.UTF_8), 3), channel);
        verify(channel).basicReject(3, false);
        verifyNoInteractions(worker);
    }

    @Test
    void requeuesTheJobWhenTheWorkerStops() throws Exception {
        when(worker.process(any())).thenThrow(new InterruptedException());
        listener.onMessage(message(json.writeValueAsBytes(job), 9), channel);
        verify(channel).basicNack(9, false, true);
        assertThat(Thread.interrupted()).isTrue();
    }

    private static Message message(byte[] body, long tag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(tag);
        return new Message(body, properties);
    }
}
