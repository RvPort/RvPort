package cn.rvport.backend.worker;

import cn.rvport.backend.queue.TaskQueueMessage;
import cn.rvport.backend.task.*;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只收敛已有失败投递证据且尚未领取的任务，不按排队时长判定失败。 */
@Service
public class TaskDeliveryRecoveryService {
    private final TaskRepository tasks;
    private final TaskRuntimeHandleRepository handles;
    private final TaskDomainFinalizationService domains;
    private final TaskEventService events;
    private final TaskSecretPayloadService secrets;

    public TaskDeliveryRecoveryService(TaskRepository tasks,TaskRuntimeHandleRepository handles,
            TaskDomainFinalizationService domains,TaskEventService events,TaskSecretPayloadService secrets) {
        this.tasks=tasks;this.handles=handles;this.domains=domains;this.events=events;this.secrets=secrets;
    }
    @Transactional
    public boolean reconcile(TaskQueueMessage message) {
        var task=tasks.lockById(message.taskId());
        if(task==null || !Objects.equals(task.projectId(),message.projectId()) || !task.type().equals(message.taskType())) return false;
        if(task.status()!=TaskStatus.PENDING && task.status()!=TaskStatus.QUEUED) return true;
        if(handles.findActiveByTaskId(task.id()).isPresent()) return false;
        String reason="任务领取失败且投递重试已耗尽 / Task delivery retries were exhausted before execution";
        var failed=tasks.completeIfActive(task.id(),TaskStatus.FAILED,reason,TaskErrorCode.DELIVERY_EXHAUSTED,reason);
        if(failed==null) throw new IllegalStateException("TASK_RECOVERY_STATE_CONFLICT");
        domains.finish(failed);
        events.append(failed.projectId(),failed.id(),TaskEventType.FAILED,TaskStatus.FAILED,failed.progress(),reason);
        secrets.deleteByTaskId(failed.id());
        return true;
    }
}
