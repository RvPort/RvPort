package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.rvport.backend.task.*;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TaskAttemptServiceTests {
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskRuntimeHandleRepository handles = mock(TaskRuntimeHandleRepository.class);
    private final WorkerInstanceRepository workers = mock(WorkerInstanceRepository.class);
    private final TaskAttemptService service = new TaskAttemptService(tasks, handles, workers);
    private final UUID id = UUID.randomUUID();
    private final UUID handleId = UUID.randomUUID();

    @Test
    void claimLocksWorkerAndCreatesDistinctOwnershipOrRollsBack() {
        assertThatThrownBy(() -> service.claim(id, "worker")).isInstanceOf(TaskRetryableException.class).hasMessage("TASK_WORKER_NOT_REGISTERED");
        verifyNoInteractions(tasks, handles);
        when(workers.lockForClaim("worker")).thenReturn(true);
        assertThat(service.claim(id, "worker")).isNull();
        TaskView running = task(TaskStatus.RUNNING);
        when(tasks.claim(id, "任务开始执行")).thenReturn(running);
        assertThatThrownBy(() -> service.claim(id, "worker")).isInstanceOf(TaskAttemptLostException.class);
        when(handles.createTaskHandle(id, running.projectId(), "worker")).thenReturn(handle("other", TaskRuntimeHandleStatus.ACTIVE));
        assertThatThrownBy(() -> service.claim(id, "worker")).isInstanceOf(TaskAttemptLostException.class);
        var handle = handle("worker", TaskRuntimeHandleStatus.ACTIVE);
        when(handles.createTaskHandle(id, running.projectId(), "worker")).thenReturn(handle);
        var claim = service.claim(id, "worker");
        assertThat(claim.task()).isEqualTo(running);
        assertThat(claim.handle()).isEqualTo(handle);
    }

    @Test
    void publicationRejectsMissingTerminalAndDifferentAttemptWithoutExecutingOperation() {
        Supplier<String> write = mock(Supplier.class);
        assertThatThrownBy(() -> service.publish(id, handleId, write)).isInstanceOf(TaskAttemptLostException.class);
        for (TaskStatus status : TaskStatus.values()) {
            if (status == TaskStatus.RUNNING) continue;
            when(tasks.lockById(id)).thenReturn(task(status));
            assertThatThrownBy(() -> service.publish(id, handleId, write)).isInstanceOf(TaskAttemptLostException.class);
        }
        when(tasks.lockById(id)).thenReturn(task(TaskStatus.RUNNING));
        assertThatThrownBy(() -> service.publish(id, handleId, write)).isInstanceOf(TaskAttemptLostException.class);
        when(handles.findActiveByTaskId(id)).thenReturn(Optional.of(handle("worker", TaskRuntimeHandleStatus.ACTIVE)));
        assertThatThrownBy(() -> service.publish(id, UUID.randomUUID(), write)).isInstanceOf(TaskAttemptLostException.class);
        when(handles.findActiveByTaskId(id)).thenReturn(Optional.of(handle("worker", TaskRuntimeHandleStatus.CANCEL_REQUESTED)));
        assertThatThrownBy(() -> service.publish(id, handleId, write)).isInstanceOf(TaskAttemptLostException.class);
        verifyNoInteractions(write);
    }

    @Test
    void publicationExecutesUnderTaskLockAndPropagatesFailureForRollback() {
        when(tasks.lockById(id)).thenReturn(task(TaskStatus.RUNNING));
        when(handles.findActiveByTaskId(id)).thenReturn(Optional.of(handle("worker", TaskRuntimeHandleStatus.ACTIVE)));
        assertThat(service.publish(id, handleId, () -> "saved")).isEqualTo("saved");
        var order = inOrder(tasks, handles);
        order.verify(tasks).lockById(id);
        order.verify(handles).findActiveByTaskId(id);
        assertThatThrownBy(() -> service.publish(id, handleId, () -> { throw new IllegalStateException("db"); }))
                .isInstanceOf(IllegalStateException.class).hasMessage("db");
    }

    private TaskView task(TaskStatus status) {
        var now = OffsetDateTime.now();
        return new TaskView(id, id, "KB_SCAN", status, 10, "test", id, now, now, now, null, 300, null, null, 0);
    }

    private TaskRuntimeHandleView handle(String worker, TaskRuntimeHandleStatus status) {
        var now = OffsetDateTime.now();
        return new TaskRuntimeHandleView(handleId, id, id, worker, TaskRuntimeHandleType.TASK, status,
                null, null, null, null, null, null, now, now, null, null, null);
    }
}
