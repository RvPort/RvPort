package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.queue.TaskQueueMessage;
import cn.rvport.backend.task.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TaskDeliveryRecoveryServiceTests {
    final TaskRepository tasks=mock(TaskRepository.class);
    final TaskRuntimeHandleRepository handles=mock(TaskRuntimeHandleRepository.class);
    final TaskDomainFinalizationService domains=mock(TaskDomainFinalizationService.class);
    final TaskEventService events=mock(TaskEventService.class);
    final TaskSecretPayloadService secrets=mock(TaskSecretPayloadService.class);
    final TaskDeliveryRecoveryService service=new TaskDeliveryRecoveryService(tasks,handles,domains,events,secrets);
    final UUID id=UUID.randomUUID(),project=UUID.randomUUID();
    final TaskQueueMessage message=new TaskQueueMessage(id,"REPORT_GENERATE",project,null,3);
    TaskView task(TaskStatus state) {
        return new TaskView(id,project,"REPORT_GENERATE",state,0,null,null,null,null,null,null,60,null,null,0);
    }
    @Test void finalizesPendingAndQueuedAfterLockAndIsIdempotent() {
        for(var state:List.of(TaskStatus.PENDING,TaskStatus.QUEUED)) {
            reset(tasks,handles,domains,events,secrets);
            doReturn(task(state)).when(tasks).lockById(id);var failed=task(TaskStatus.FAILED);
            when(tasks.completeIfActive(eq(id),eq(TaskStatus.FAILED),anyString(),eq(TaskErrorCode.DELIVERY_EXHAUSTED),anyString())).thenReturn(failed);
            assertThat(service.reconcile(message)).isTrue();
            var order=inOrder(tasks,handles,domains,events,secrets);
            order.verify(tasks).lockById(id);order.verify(handles).findActiveByTaskId(id);
            order.verify(tasks).completeIfActive(eq(id),eq(TaskStatus.FAILED),anyString(),eq(TaskErrorCode.DELIVERY_EXHAUSTED),anyString());
            order.verify(domains).finish(failed);order.verify(events).append(eq(project),eq(id),eq(TaskEventType.FAILED),eq(TaskStatus.FAILED),any(),anyString());
            order.verify(secrets).deleteByTaskId(id);
            when(tasks.lockById(id)).thenReturn(failed);assertThat(service.reconcile(message)).isTrue();
            verify(domains,times(1)).finish(failed);
        }
    }
    @Test void protectsRunningTerminalUnknownMismatchedAndOwnedTasks() {
        assertThat(service.reconcile(message)).isFalse();
        for(var status:List.of(TaskStatus.RUNNING,TaskStatus.SUCCEEDED,TaskStatus.CANCELLED,TaskStatus.FAILED,TaskStatus.TIMEOUT)) {
            doReturn(task(status)).when(tasks).lockById(id);assertThat(service.reconcile(message)).isTrue();
        }
        var queued=task(TaskStatus.QUEUED);when(tasks.lockById(id)).thenReturn(queued);
        assertThat(service.reconcile(new TaskQueueMessage(id,"wrong",project,null,3))).isFalse();
        assertThat(service.reconcile(new TaskQueueMessage(id,"REPORT_GENERATE",UUID.randomUUID(),null,3))).isFalse();
        when(handles.findActiveByTaskId(id)).thenReturn(Optional.of(new TaskRuntimeHandleView(UUID.randomUUID(),id,project,
                "worker",TaskRuntimeHandleType.LOCAL_PROCESS,TaskRuntimeHandleStatus.ACTIVE,1L,null,null,null,null,null,null,null,null,null,null)));
        assertThat(service.reconcile(message)).isFalse();verifyNoInteractions(domains,events,secrets);
    }
    @Test void databaseAndWriteConflictsRemainRetryableWithoutConfirming() {
        when(tasks.lockById(id)).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(()->service.reconcile(message)).hasMessage("database unavailable");
        reset(tasks);doReturn(task(TaskStatus.QUEUED)).when(tasks).lockById(id);
        assertThatThrownBy(()->service.reconcile(message)).hasMessage("TASK_RECOVERY_STATE_CONFLICT");
        verifyNoInteractions(domains,events,secrets);
    }
}
