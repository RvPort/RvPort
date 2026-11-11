package cn.rvport.backend.task;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.queue.TaskCancelQueueClient;
import cn.rvport.backend.queue.TaskCancelQueueMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskEventService taskEventService;
    private final TaskDispatchService taskDispatchService;
    private final ObjectMapper objectMapper;
    private final ProjectAccessService projectAccessService;
    private final TaskRuntimeHandleRepository runtimeHandleRepository;
    private final TaskCancelQueueClient taskCancelQueueClient;
    private final TaskLogSseBroker taskLogSseBroker;
    private final TaskMetadataResolver taskMetadataResolver;

    public TaskService(
            TaskRepository taskRepository,
            TaskEventService taskEventService,
            TaskDispatchService taskDispatchService,
            ObjectMapper objectMapper,
            ProjectAccessService projectAccessService,
            TaskRuntimeHandleRepository runtimeHandleRepository,
            TaskCancelQueueClient taskCancelQueueClient,
            TaskLogSseBroker taskLogSseBroker,
            TaskMetadataResolver taskMetadataResolver
    ) {
        this.taskRepository = taskRepository;
        this.taskEventService = taskEventService;
        this.taskDispatchService = taskDispatchService;
        this.objectMapper = objectMapper;
        this.projectAccessService = projectAccessService;
        this.runtimeHandleRepository = runtimeHandleRepository;
        this.taskCancelQueueClient = taskCancelQueueClient;
        this.taskLogSseBroker = taskLogSseBroker;
        this.taskMetadataResolver = taskMetadataResolver;
    }

    @Transactional
    public TaskResponse create(TaskCreateCommand command) {
        validateCreate(command);
        command = taskMetadataResolver.resolve(command);
        TaskView task = taskRepository.create(command, toJson(command.inputSnapshot()));
        taskEventService.append(
                task.projectId(),
                task.id(),
                TaskEventType.CREATED,
                task.status(),
                task.progress(),
                task.message() == null ? "任务已创建" : task.message()
        );
        dispatchAfterCommit(task);
        return TaskResponse.from(task);
    }

    @Transactional(readOnly = true)
    public TaskResponse get(UUID taskId, UUID actorUserId, boolean superAdmin) {
        return TaskResponse.from(projectAccessService.requireTaskAccess(taskId, actorUserId, superAdmin));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(UUID projectId, TaskListRequest request, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        long total = taskRepository.count(projectId, request.status(), request.type());
        List<TaskResponse> items = taskRepository.list(projectId, request.status(), request.type(), page, pageSize)
                .stream()
                .map(TaskResponse::from)
                .toList();
        return PageResponse.of(items, page, pageSize, total);
    }

    @Transactional
    public TaskResponse cancel(UUID taskId, UUID actorUserId, boolean superAdmin) {
        TaskView task = projectAccessService.requireTaskAccess(taskId, actorUserId, superAdmin);
        if (task.status() == TaskStatus.RUNNING) {
            TaskView cancelled = taskRepository.cancelRunning(taskId, actorUserId, "USER_REQUESTED");
            if (cancelled == null) {
                return TaskResponse.from(requireTask(taskId));
            }
            taskEventService.append(
                    cancelled.projectId(),
                    cancelled.id(),
                    TaskEventType.CANCELLED,
                    TaskStatus.CANCELLED,
                    cancelled.progress(),
                    cancelled.message()
            );
            taskLogSseBroker.publishTerminal(cancelled);
            runtimeHandleRepository.findActiveByTaskId(taskId)
                    .ifPresent(handle -> publishCancelAfterCommit(new TaskCancelQueueMessage(
                            cancelled.id(),
                            handle.id(),
                            handle.workerInstanceId(),
                            java.time.OffsetDateTime.now(),
                            "USER_REQUESTED"
                    )));
            return TaskResponse.from(cancelled);
        }
        if (taskRepository.isTerminal(task.status())) {
            return TaskResponse.from(task);
        }
        TaskView cancelled = taskRepository.cancelPendingOrQueued(taskId, actorUserId, "USER_REQUESTED");
        if (cancelled == null) {
            return TaskResponse.from(requireTask(taskId));
        }
        taskEventService.append(
                cancelled.projectId(),
                cancelled.id(),
                TaskEventType.CANCELLED,
                TaskStatus.CANCELLED,
                cancelled.progress(),
                cancelled.message()
        );
        taskLogSseBroker.publishTerminal(cancelled);
        return TaskResponse.from(cancelled);
    }

    TaskView requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在"));
    }

    @Transactional(readOnly = true)
    public TaskView getInternal(UUID taskId) {
        return requireTask(taskId);
    }

    /** 只接受新任务显式持久化且与操作者一致的配置主体；旧任务不推断身份。 */
    public UUID configurationUserId(UUID taskId) {
        var task = taskRepository.findRecordById(taskId).orElse(null);
        if (task == null || task.getInputSnapshot() == null) { return null; }
        try {
            var root = objectMapper.readTree(task.getInputSnapshot().data());
            var value = root == null ? null : root.get("configurationUserId");
            if (value == null || !value.isTextual()) { return null; }
            UUID actor = UUID.fromString(value.asText());
            return actor.equals(task.getCreatedBy()) ? actor : null;
        } catch (java.io.IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    private void validateCreate(TaskCreateCommand command) {
        if (command.projectId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "项目 ID 不能为空");
        }
        if (command.createdBy() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "任务创建人不能为空");
        }
        if (command.type() == null || command.type().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "任务类型不能为空");
        }
    }

    private String toJson(Map<String, ?> inputSnapshot) {
        try {
            return objectMapper.writeValueAsString(inputSnapshot == null ? Map.of() : inputSnapshot);
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TASK_INPUT_INVALID", "任务输入快照无法序列化");
        }
    }

    private void dispatchAfterCommit(TaskView task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskDispatchService.publishAndMarkQueued(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskDispatchService.publishAndMarkQueued(task);
            }
        });
    }

    private void publishCancelAfterCommit(TaskCancelQueueMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskCancelQueueClient.publish(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskCancelQueueClient.publish(message);
            }
        });
    }
}
