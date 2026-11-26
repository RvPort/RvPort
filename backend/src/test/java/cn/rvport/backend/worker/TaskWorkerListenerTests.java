package cn.rvport.backend.worker;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import cn.rvport.backend.config.TaskQueueProperties;
import cn.rvport.backend.queue.TaskQueueClient;
import cn.rvport.backend.queue.TaskQueueMessage;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@ExtendWith(MockitoExtension.class)
class TaskWorkerListenerTests {

    @Test void queueConsumerWaitsForRegisteredHeartbeat() throws Exception {
        var annotation = TaskWorkerListener.class.getMethod("onMessage", TaskQueueMessage.class, Message.class, Channel.class)
                .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        org.assertj.core.api.Assertions.assertThat(annotation.autoStartup()).isEqualTo("false");
    }

    @Mock
    private TaskWorkerService taskWorkerService;

    @Mock
    private Channel channel;

    @Mock
    private TaskQueueClient taskQueueClient;

    @Test
    void acknowledgesMessageAfterSuccessfulProcessing() throws IOException {
        TaskWorkerListener listener = new TaskWorkerListener(taskWorkerService, properties(), taskQueueClient);
        Message message = message(42L);
        TaskQueueMessage queueMessage = queueMessage();

        listener.onMessage(queueMessage, message, channel);

        verify(taskWorkerService).process(queueMessage.taskId(), 1, 3);
        verify(channel).basicAck(42L, false);
    }

    @Test
    void republishesNextAttemptWhenTaskRequestsRetry() throws IOException {
        TaskWorkerListener listener = new TaskWorkerListener(taskWorkerService, properties(), taskQueueClient);
        Message message = message(43L);
        TaskQueueMessage queueMessage = queueMessage();
        doThrow(new TaskRetryableException("retry", new RuntimeException("boom")))
                .when(taskWorkerService).process(queueMessage.taskId(), 1, 3);

        listener.onMessage(queueMessage, message, channel);

        verify(taskQueueClient).publish(queueMessage.nextAttempt());
        verify(channel).basicAck(43L, false);
    }

    @Test
    void nacksWithoutRequeueAtMaxAttemptsSoRabbitRoutesToDeadLetterQueue() throws IOException {
        TaskWorkerListener listener = new TaskWorkerListener(taskWorkerService, properties(), taskQueueClient);
        Message message = message(44L);
        TaskQueueMessage queueMessage = new TaskQueueMessage(UUID.randomUUID(), "ENV_TEST_CONNECTION", UUID.randomUUID(), OffsetDateTime.now(), 3);
        doThrow(new IllegalStateException("database unavailable")).when(taskWorkerService).process(queueMessage.taskId(), 3, 3);

        listener.onMessage(queueMessage, message, channel);

        verify(channel).basicNack(44L, false, false);
    }

    @Test
    void nacksRetryableFailureWithoutRequeueAtMaxAttempts() throws IOException {
        TaskWorkerListener listener = new TaskWorkerListener(taskWorkerService, properties(), taskQueueClient);
        Message message = message(45L);
        TaskQueueMessage queueMessage = new TaskQueueMessage(
                UUID.randomUUID(), "ENV_TEST_CONNECTION", UUID.randomUUID(), OffsetDateTime.now(), 3);
        doThrow(new TaskRetryableException("retry", new RuntimeException("boom")))
                .when(taskWorkerService).process(queueMessage.taskId(), 3, 3);

        listener.onMessage(queueMessage, message, channel);

        verify(channel).basicNack(45L, false, false);
    }

    @Test
    void republishesUnexpectedFailureBeforeMaxAttempts() throws IOException {
        TaskWorkerListener listener = new TaskWorkerListener(taskWorkerService, properties(), taskQueueClient);
        Message message = message(46L);
        TaskQueueMessage queueMessage = queueMessage();
        doThrow(new IllegalStateException("database unavailable"))
                .when(taskWorkerService).process(queueMessage.taskId(), 1, 3);

        listener.onMessage(queueMessage, message, channel);

        verify(taskQueueClient).publish(queueMessage.nextAttempt());
        verify(channel).basicAck(46L, false);
    }

    private Message message(long deliveryTag) {
        return message(deliveryTag, Map.of());
    }

    private Message message(long deliveryTag, Map<String, Object> headers) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        headers.forEach(properties::setHeader);
        return new Message(new byte[0], properties);
    }

    private TaskQueueMessage queueMessage() {
        return new TaskQueueMessage(UUID.randomUUID(), "ENV_TEST_CONNECTION", UUID.randomUUID(), OffsetDateTime.now());
    }

    private TaskQueueProperties properties() {
        return new TaskQueueProperties(
                "rvport.tasks",
                "rvport.tasks.default",
                "task.default",
                "rvport.tasks.dlx",
                "rvport.tasks.default.dlq",
                "task.default.dead",
                3
        );
    }
}
