package cn.rvport.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.queue.TaskCancelQueueClient;
import cn.rvport.backend.queue.TaskCancelQueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class TaskServiceTests {

    @Test void configurationIdentityRequiresExplicitSnapshotMatchingOriginalActor() {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        TaskService service = service();
        assertThat(service.configurationUserId(id)).isNull();
        var record = new cn.rvport.backend.jooq.tables.records.TasksRecord();
        record.setCreatedBy(actor);
        when(taskRepository.findRecordById(id)).thenReturn(Optional.of(record));
        assertThat(service.configurationUserId(id)).isNull();
        for (String invalid : java.util.List.of("", "null", "{}", "{\"configurationUserId\":null}",
                "{\"configurationUserId\":1}", "{\"configurationUserId\":\"invalid\"}", "[",
                "{\"configurationUserId\":\"" + UUID.randomUUID() + "\"}")) {
            record.setInputSnapshot(org.jooq.JSONB.valueOf(invalid));
            assertThat(service.configurationUserId(id)).isNull();
        }
        record.setInputSnapshot(org.jooq.JSONB.valueOf("{\"configurationUserId\":\"" + actor + "\"}"));
        assertThat(service.configurationUserId(id)).isEqualTo(actor);
        record.setCreatedBy(null);
        assertThat(service.configurationUserId(id)).isNull();
    }

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskEventService taskEventService;

    @Mock
    private TaskDispatchService taskDispatchService;

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private TaskRuntimeHandleRepository runtimeHandleRepository;

    @Mock
    private TaskCancelQueueClient taskCancelQueueClient;

    @Mock
    private TaskLogSseBroker taskLogSseBroker;

    @Test
    void createsPendingTaskAndDispatchesAfterPersistence() {
        TaskService service = service();
        UUID projectId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        TaskView created = task(projectId, creatorId, TaskStatus.PENDING);
        when(taskRepository.create(any(), eq("{\"scope\":\"ALL\"}"))).thenReturn(created);

        TaskResponse response = service.create(new TaskCreateCommand(
                projectId,
                "KB_SCAN",
                Map.of("scope", "ALL"),
                creatorId,
                300,
                "开始扫描"
        ));

        assertThat(response.status()).isEqualTo(TaskStatus.PENDING);
        verify(taskEventService).append(projectId, created.id(), TaskEventType.CREATED, TaskStatus.PENDING, 0, "开始扫描");
        verify(taskDispatchService).publishAndMarkQueued(created);
    }

    @Test
    void cancelsPendingTaskAndAppendsCancelledEvent() {
        TaskService service = service();
        TaskView pending = task(UUID.randomUUID(), UUID.randomUUID(), TaskStatus.PENDING);
        TaskView cancelled = new TaskView(
                pending.id(),
                pending.projectId(),
                pending.type(),
                TaskStatus.CANCELLED,
                pending.progress(),
                "任务已取消",
                pending.createdBy(),
                pending.createdAt(),
                pending.queuedAt(),
                pending.startedAt(),
                OffsetDateTime.now(),
                pending.timeoutSeconds(),
                pending.errorCode(),
                pending.errorMessage(),
                pending.retryCount()
        );
        when(projectAccessService.requireTaskAccess(pending.id(), pending.createdBy(), false)).thenReturn(pending);
        when(taskRepository.cancelPendingOrQueued(pending.id(), pending.createdBy(), "USER_REQUESTED")).thenReturn(cancelled);

        TaskResponse response = service.cancel(pending.id(), pending.createdBy(), false);

        assertThat(response.status()).isEqualTo(TaskStatus.CANCELLED);
        verify(taskEventService).append(
                cancelled.projectId(),
                cancelled.id(),
                TaskEventType.CANCELLED,
                TaskStatus.CANCELLED,
                cancelled.progress(),
                "任务已取消"
        );
    }

    @Test
    void cancelsRunningTaskAndPublishesCancelMessage() {
        TaskService service = service();
        TaskView running = task(UUID.randomUUID(), UUID.randomUUID(), TaskStatus.RUNNING);
        TaskView cancelled = new TaskView(
                running.id(), running.projectId(), running.type(), TaskStatus.CANCELLED, 0, "任务已取消，正在清理运行资源",
                running.createdBy(), running.createdAt(), running.queuedAt(), running.startedAt(), OffsetDateTime.now(),
                running.timeoutSeconds(), null, null, running.retryCount());
        TaskRuntimeHandleView handle = new TaskRuntimeHandleView(
                UUID.randomUUID(),
                running.id(),
                running.projectId(),
                "worker-1",
                TaskRuntimeHandleType.TASK,
                TaskRuntimeHandleStatus.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null,
                null,
                null
        );
        when(projectAccessService.requireTaskAccess(running.id(), running.createdBy(), false)).thenReturn(running);
        when(taskRepository.cancelRunning(running.id(), running.createdBy(), "USER_REQUESTED")).thenReturn(cancelled);
        when(runtimeHandleRepository.findActiveByTaskId(running.id())).thenReturn(Optional.of(handle));

        TaskResponse response = service.cancel(running.id(), running.createdBy(), false);

        assertThat(response.status()).isEqualTo(TaskStatus.CANCELLED);
        verify(taskEventService).append(cancelled.projectId(), cancelled.id(), TaskEventType.CANCELLED,
                TaskStatus.CANCELLED, cancelled.progress(), cancelled.message());
        verify(taskCancelQueueClient).publish(any(TaskCancelQueueMessage.class));
    }

    @Test
    void rejectsMissingTaskAndInvalidCreateCommand() {
        TaskService service = service();
        UUID taskId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(projectAccessService.requireTaskAccess(taskId, actorId, false))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在"));

        assertThatThrownBy(() -> service.get(taskId, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("任务不存在");
        assertThatThrownBy(() -> service.cancel(taskId, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("任务不存在");

        assertThatThrownBy(() -> service.create(new TaskCreateCommand(null, "KB_SCAN", Map.of(), UUID.randomUUID(), null, "msg")))
                .isInstanceOf(ApiException.class)
                .hasMessage("项目 ID 不能为空");
        assertThatThrownBy(() -> service.create(new TaskCreateCommand(UUID.randomUUID(), "KB_SCAN", Map.of(), null, null, "msg")))
                .isInstanceOf(ApiException.class)
                .hasMessage("任务创建人不能为空");
        assertThatThrownBy(() -> service.create(new TaskCreateCommand(UUID.randomUUID(), " ", Map.of(), UUID.randomUUID(), null, "msg")))
                .isInstanceOf(ApiException.class)
                .hasMessage("任务类型不能为空");
        assertThatThrownBy(() -> service.create(new TaskCreateCommand(UUID.randomUUID(), null, Map.of(), UUID.randomUUID(), null, "msg")))
                .isInstanceOf(ApiException.class)
                .hasMessage("任务类型不能为空");
    }

    @Test
    void rejectsUnserializableInputSnapshot() {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("boom") {
                };
            }
        };
        TaskService service = new TaskService(taskRepository, taskEventService, taskDispatchService, failingMapper,
                projectAccessService, runtimeHandleRepository, taskCancelQueueClient, taskLogSseBroker, new TaskMetadataResolver());

        assertThatThrownBy(() -> service.create(new TaskCreateCommand(
                UUID.randomUUID(),
                "KB_SCAN",
                Map.of("scope", "ALL"),
                UUID.randomUUID(),
                null,
                "msg"
        ))).isInstanceOf(ApiException.class)
                .hasMessage("任务输入快照无法序列化");
    }

    @Test
    void terminalCancelReturnsCurrentTaskWithoutAppendingEvent() {
        TaskService service = service();
        TaskView succeeded = task(UUID.randomUUID(), UUID.randomUUID(), TaskStatus.SUCCEEDED);
        when(projectAccessService.requireTaskAccess(succeeded.id(), succeeded.createdBy(), false)).thenReturn(succeeded);
        when(taskRepository.isTerminal(TaskStatus.SUCCEEDED)).thenReturn(true);

        TaskResponse response = service.cancel(succeeded.id(), succeeded.createdBy(), false);

        assertThat(response.status()).isEqualTo(TaskStatus.SUCCEEDED);
        verify(taskRepository, never()).cancelPendingOrQueued(any());
        verify(taskEventService, never()).append(any(), any(), eq(TaskEventType.CANCELLED), any(), any(), any());
    }

    @Test
    void cancelsQueuedTaskAndAppendsCancelledEvent() {
        TaskService service = service();
        TaskView queued = task(UUID.randomUUID(), UUID.randomUUID(), TaskStatus.QUEUED);
        TaskView cancelled = new TaskView(
                queued.id(), queued.projectId(), queued.type(), TaskStatus.CANCELLED, 0, "任务已取消",
                queued.createdBy(), queued.createdAt(), queued.queuedAt(), queued.startedAt(), OffsetDateTime.now(),
                queued.timeoutSeconds(), null, null, queued.retryCount());
        when(projectAccessService.requireTaskAccess(queued.id(), queued.createdBy(), false)).thenReturn(queued);
        when(taskRepository.isTerminal(TaskStatus.QUEUED)).thenReturn(false);
        when(taskRepository.cancelPendingOrQueued(queued.id(), queued.createdBy(), "USER_REQUESTED")).thenReturn(cancelled);

        TaskResponse response = service.cancel(queued.id(), queued.createdBy(), false);

        assertThat(response.status()).isEqualTo(TaskStatus.CANCELLED);
        verify(taskEventService).append(cancelled.projectId(), cancelled.id(), TaskEventType.CANCELLED,
                TaskStatus.CANCELLED, cancelled.progress(), cancelled.message());
    }

    @Test
    void listsGetsAndReturnsInternalTasks() {
        TaskService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TaskView task = task(projectId, actorId, TaskStatus.RUNNING);
        TaskListRequest request = new TaskListRequest(TaskStatus.RUNNING, "KB_SCAN", null, null);
        when(taskRepository.count(projectId, TaskStatus.RUNNING, "KB_SCAN")).thenReturn(1L);
        when(taskRepository.list(projectId, TaskStatus.RUNNING, "KB_SCAN", 1, 20)).thenReturn(java.util.List.of(task));
        when(projectAccessService.requireTaskAccess(task.id(), actorId, false)).thenReturn(task);
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));

        assertThat(service.list(projectId, request, actorId, false).items()).hasSize(1);
        assertThat(service.get(task.id(), actorId, false).id()).isEqualTo(task.id());
        assertThat(service.getInternal(task.id())).isEqualTo(task);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void createUsesDefaultMessageAndDefersDispatchUntilCommit() {
        TaskService service = service();
        UUID projectId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        TaskView created = new TaskView(
                UUID.randomUUID(), projectId, "KB_SCAN", TaskStatus.PENDING, 0, null, creatorId,
                OffsetDateTime.now(), null, null, null, 300, null, null, 0);
        when(taskRepository.create(any(), eq("{}"))).thenReturn(created);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.create(new TaskCreateCommand(projectId, "KB_SCAN", null, creatorId, 300, null));
            verify(taskDispatchService, never()).publishAndMarkQueued(any());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(taskDispatchService).publishAndMarkQueued(created);
            verify(taskEventService).append(projectId, created.id(), TaskEventType.CREATED,
                    TaskStatus.PENDING, 0, "任务已创建");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void concurrentCancellationFallbacksReloadCurrentTask() {
        TaskService service = service();
        TaskView running = task(UUID.randomUUID(), UUID.randomUUID(), TaskStatus.RUNNING);
        TaskView queued = task(running.projectId(), running.createdBy(), TaskStatus.QUEUED);
        when(projectAccessService.requireTaskAccess(running.id(), running.createdBy(), false)).thenReturn(running);
        when(taskRepository.cancelRunning(running.id(), running.createdBy(), "USER_REQUESTED")).thenReturn(null);
        when(taskRepository.findById(running.id())).thenReturn(Optional.of(running));
        assertThat(service.cancel(running.id(), running.createdBy(), false).status()).isEqualTo(TaskStatus.RUNNING);

        when(projectAccessService.requireTaskAccess(queued.id(), queued.createdBy(), false)).thenReturn(queued);
        when(taskRepository.isTerminal(TaskStatus.QUEUED)).thenReturn(false);
        when(taskRepository.cancelPendingOrQueued(queued.id(), queued.createdBy(), "USER_REQUESTED")).thenReturn(null);
        when(taskRepository.findById(queued.id())).thenReturn(Optional.of(queued));
        assertThat(service.cancel(queued.id(), queued.createdBy(), false).status()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void runningCancellationWithoutHandleDoesNotPublishQueueMessage() {
        TaskService service = service();
        TaskView running = task(UUID.randomUUID(), UUID.randomUUID(), TaskStatus.RUNNING);
        TaskView cancelled = task(running.projectId(), running.createdBy(), TaskStatus.CANCELLED);
        when(projectAccessService.requireTaskAccess(running.id(), running.createdBy(), false)).thenReturn(running);
        when(taskRepository.cancelRunning(running.id(), running.createdBy(), "USER_REQUESTED")).thenReturn(cancelled);
        when(runtimeHandleRepository.findActiveByTaskId(running.id())).thenReturn(Optional.empty());

        assertThat(service.cancel(running.id(), running.createdBy(), false).status()).isEqualTo(TaskStatus.CANCELLED);
        verify(taskCancelQueueClient, never()).publish(any());
    }

    private TaskView task(UUID projectId, UUID creatorId, TaskStatus status) {
        return new TaskView(
                UUID.randomUUID(),
                projectId,
                "KB_SCAN",
                status,
                0,
                "开始扫描",
                creatorId,
                OffsetDateTime.now(),
                null,
                null,
                null,
                300,
                null,
                null,
                0
        );
    }

    private TaskService service() {
        return new TaskService(taskRepository, taskEventService, taskDispatchService, new ObjectMapper(),
                projectAccessService, runtimeHandleRepository, taskCancelQueueClient, taskLogSseBroker, new TaskMetadataResolver());
    }
}
