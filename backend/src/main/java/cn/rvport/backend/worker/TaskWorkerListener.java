package cn.rvport.backend.worker;

import cn.rvport.backend.config.TaskQueueProperties;
import cn.rvport.backend.queue.TaskQueueClient;
import cn.rvport.backend.queue.TaskQueueMessage;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rvport.worker", name = "enabled", havingValue = "true")
public class TaskWorkerListener {

    public static final String TASK_LISTENER_ID = "taskWorkerListener";

    private static final Logger log = LoggerFactory.getLogger(TaskWorkerListener.class);

    private final TaskWorkerService taskWorkerService;
    private final TaskQueueProperties taskQueueProperties;
    private final TaskQueueClient taskQueueClient;

    public TaskWorkerListener(
            TaskWorkerService taskWorkerService,
            TaskQueueProperties taskQueueProperties,
            TaskQueueClient taskQueueClient
    ) {
        this.taskWorkerService = taskWorkerService;
        this.taskQueueProperties = taskQueueProperties;
        this.taskQueueClient = taskQueueClient;
    }

    @RabbitListener(id = TASK_LISTENER_ID, queues = "${rvport.queue.task.queue}", containerFactory = "rabbitListenerContainerFactory", autoStartup = "false")
    public void onMessage(TaskQueueMessage taskMessage, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int attempt = taskMessage.attempt();
        try {
            taskWorkerService.process(taskMessage.taskId(), attempt, taskQueueProperties.maxAttempts());
            channel.basicAck(deliveryTag, false);
        } catch (TaskRetryableException ex) {
            if (attempt < taskQueueProperties.maxAttempts()) {
                taskQueueClient.publish(taskMessage.nextAttempt());
                channel.basicAck(deliveryTag, false);
                return;
            }
            log.error("Task retry exhausted taskId={} attempt={} maxAttempts={}",
                    taskMessage.taskId(), attempt, taskQueueProperties.maxAttempts(), ex);
            channel.basicNack(deliveryTag, false, false);
        } catch (RuntimeException ex) {
            if (attempt < taskQueueProperties.maxAttempts()) {
                taskQueueClient.publish(taskMessage.nextAttempt());
                channel.basicAck(deliveryTag, false);
                return;
            }
            log.error("Task worker failed after database failure handling taskId={} attempt={} maxAttempts={} routeToDlq=true",
                    taskMessage.taskId(), attempt, taskQueueProperties.maxAttempts(), ex);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
