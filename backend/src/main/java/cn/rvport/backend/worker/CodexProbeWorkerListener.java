package cn.rvport.backend.worker;

import cn.rvport.backend.modelprofile.CodexProbeDispatcher;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="rvport.worker",name="enabled",havingValue="true")
public class CodexProbeWorkerListener {
    private final CodexProbeDispatcher dispatcher;
    public CodexProbeWorkerListener(CodexProbeDispatcher dispatcher) { this.dispatcher=dispatcher; }

    @RabbitListener(queues=CodexProbeDispatcher.QUEUE,ackMode="AUTO",concurrency="1")
    public void onMessage(String id) {
        UUID jobId;
        try { jobId = UUID.fromString(id); }
        catch (IllegalArgumentException ex) {
            throw new org.springframework.amqp.AmqpRejectAndDontRequeueException("INVALID_PROBE_JOB_ID");
        }
        dispatcher.process(jobId);
    }
}
