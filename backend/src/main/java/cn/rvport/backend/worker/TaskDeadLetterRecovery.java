package cn.rvport.backend.worker;

import cn.rvport.backend.config.TaskQueueProperties;
import cn.rvport.backend.queue.TaskQueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.MessageProperties;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 提交后确认死信；数据库故障时保留消息，固定间隔再试。 */
@Component
@ConditionalOnProperty(prefix="rvport.worker",name="enabled",havingValue="true")
public class TaskDeadLetterRecovery {
    private static final Logger log=LoggerFactory.getLogger(TaskDeadLetterRecovery.class);
    private final RabbitTemplate rabbit;
    private final TaskQueueProperties properties;
    private final TaskDeliveryRecoveryService recovery;
    private final ObjectMapper mapper;
    public TaskDeadLetterRecovery(RabbitTemplate rabbit,TaskQueueProperties properties,TaskDeliveryRecoveryService recovery,ObjectMapper mapper) {
        this.rabbit=rabbit;this.properties=properties;this.recovery=recovery;this.mapper=mapper;
    }
    @Scheduled(initialDelay=30000,fixedDelay=30000)
    public void recover() {
        try {
            rabbit.execute(channel->{ drain(channel);return null; });
        } catch(RuntimeException ex) {
            log.warn("Task delivery recovery deferred errorType={}",ex.getClass().getSimpleName());
        }
    }
    void drain(Channel channel) throws IOException {
        long deadline=System.nanoTime()+2_000_000_000L;
        for(int count=0;count<50 && System.nanoTime()<deadline;count++) {
            GetResponse response=channel.basicGet(properties.deadLetterQueue(),false);
            if(response==null) return;
            long tag=response.getEnvelope().getDeliveryTag();
            try {
                TaskQueueMessage message=parse(response.getBody());
                if(message==null || message.attempt()<properties.maxAttempts() || !recovery.reconcile(message)) quarantine(channel,response);
                channel.basicAck(tag,false);
            } catch(IOException | RuntimeException ex) {
                channel.basicNack(tag,false,true);
                log.warn("Task dead letter retained for later recovery errorType={}",ex.getClass().getSimpleName());
                return;
            }
        }
    }
    private TaskQueueMessage parse(byte[] body) {
        if(body.length>65536) return null;
        try { return mapper.readValue(body,TaskQueueMessage.class); }
        catch(IOException | IllegalArgumentException ex) { return null; }
    }
    private void quarantine(Channel channel,GetResponse response) throws IOException {
        String queue=properties.deadLetterQueue()+".invalid";
        channel.queueDeclare(queue,true,false,false,Map.of());
        channel.confirmSelect();
        channel.basicPublish("",queue,MessageProperties.PERSISTENT_BASIC,response.getBody());
        try { channel.waitForConfirmsOrDie(5000); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new IOException("Quarantine confirmation interrupted",ex); }
        catch(java.util.concurrent.TimeoutException ex) { throw new IOException("Quarantine confirmation timed out",ex); }
        log.warn("Unusable task dead letter quarantined; no business state changed");
    }
}
